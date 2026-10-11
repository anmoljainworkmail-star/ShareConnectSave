namespace chat_service.Services;

using chat_service.Events;
using chat_service.Repositories.Interfaces;
using chat_service.Services.Interfaces;

// See IChatLifecycleService's comment for why this exists as a class
// separate from ChatService. This class deliberately COMPOSES IChatService
// (calls it, depends on its interface) rather than re-implementing its
// not-found / not-a-participant / already-closed guard clauses a second
// time - "shared routine reuse over duplication" (ticket's own phrase),
// the same motivation CLAUDE.md gives for IOutboxService being the single
// abstraction every Kafka producer depends on.
public class ChatLifecycleService : IChatLifecycleService
{
    private readonly IChatService _chatService;
    private readonly IChatRoomRepository _chatRoomRepository;
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<ChatLifecycleService> _logger;
    private readonly TimeSpan _gracePeriod;

    public ChatLifecycleService(
        IChatService chatService,
        IChatRoomRepository chatRoomRepository,
        IServiceScopeFactory scopeFactory,
        IConfiguration configuration,
        ILogger<ChatLifecycleService> logger)
    {
        _chatService = chatService;
        _chatRoomRepository = chatRoomRepository;
        _scopeFactory = scopeFactory;
        _logger = logger;

        // No Hardcoded Config (CLAUDE.md rule 3 / this ticket's architecture
        // rules): same flat-key-with-fallback-default style Program.cs
        // already uses for MONGO_TTL_SECONDS, not a literal "300" buried in
        // this class. docker-compose.override.yml sets
        // CHAT_MET_GRACE_PERIOD_SECONDS with the same ${VAR:-default} idiom
        // every other env var in this project uses.
        var gracePeriodSeconds = int.TryParse(configuration["CHAT_MET_GRACE_PERIOD_SECONDS"], out var parsedSeconds)
            ? parsedSeconds
            : 300;
        _gracePeriod = TimeSpan.FromSeconds(gracePeriodSeconds);
    }

    public async Task<MarkMetOutcome> MarkMetSuccessfullyAsync(string chatId, long userId)
    {
        // Delegates ALL of "is this a valid request" to IChatService - this
        // class never re-derives not-found/not-a-participant/already-closed
        // itself. See this class's own header comment.
        var outcome = await _chatService.MarkMetSuccessfullyAsync(chatId, userId);

        // Guard Clause: only a Success outcome where BOTH sides have NOW
        // confirmed is eligible to start the close countdown. A single
        // confirmation (BothUsersMet false) must leave room Status
        // completely untouched - that is still just "one side said they
        // met", not a reason to begin closing.
        if (outcome.Result != MarkMetResult.Success || !outcome.BothUsersMet)
        {
            return outcome;
        }

        // Atomic OPEN -> CLOSING guard - see
        // IChatRoomRepository.TransitionToClosingAsync's comment. This is
        // what prevents two near-simultaneous "both sides just confirmed"
        // observations (see ChatService.MarkUserMetSuccessfullyAsync's own
        // atomicity note on why that scenario is rare but not impossible to
        // reason about) from each starting their own 5-minute grace timer
        // for the same room.
        var transitioned = await _chatRoomRepository.TransitionToClosingAsync(chatId);
        if (transitioned is null)
        {
            _logger.LogDebug(
                "Chat {ChatId} was no longer OPEN when both-users-met fired; grace timer not (re)started.",
                chatId);
            return outcome;
        }

        ScheduleGraceClose(chatId);

        return outcome;
    }

    // Fire-and-forget background work, scoped via IServiceScopeFactory (same
    // idiom the dotnet-mvc-controllers skill shows for OutboxRelayService) -
    // NOT the scoped IChatRoomRepository this class itself was constructed
    // with. That instance belongs to the current HTTP request's DI scope and
    // is disposed the moment this request finishes responding, long before
    // the grace period below elapses; resolving a fresh scope once the delay
    // completes is what lets this continuation safely reach MongoDB at all.
    //
    // Known limitation (explicitly called out by the ticket, not fixed
    // here): Task.Delay is in-process memory only. If this chat-service
    // instance restarts mid-grace-period, this timer is lost and the room is
    // stuck in CLOSING forever, with no fallback poller picking it back up -
    // acceptable for this ticket's scope.
    private void ScheduleGraceClose(string chatId)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(_gracePeriod);

                using var scope = _scopeFactory.CreateScope();
                var closeChatRoomService = scope.ServiceProvider.GetRequiredService<ICloseChatRoomService>();
                await closeChatRoomService.CloseRoomAsync(chatId, ChatClosedReason.MetSuccessfully);
            }
            catch (Exception ex)
            {
                // An unobserved exception on a detached Task would otherwise
                // vanish silently (or, in the worst case, crash the process
                // via UnobservedTaskException depending on host
                // configuration) - logging here is what keeps a failed
                // grace-period close visible in this service's logs instead
                // of a room just quietly never closing.
                _logger.LogError(ex, "Grace-period close failed for chat {ChatId}.", chatId);
            }
        });
    }
}
