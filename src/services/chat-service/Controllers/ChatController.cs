namespace chat_service.Controllers;

using Microsoft.AspNetCore.Mvc;
using chat_service.Contracts;
using chat_service.Dtos;
using chat_service.Extensions;
using chat_service.Services.Interfaces;

// MVC Controller (CLAUDE.md: .NET services use MVC Controllers, not Minimal
// API - see the dotnet-mvc-controllers skill for the full rationale). This
// is chat-service's FIRST controller - T035 only stood up Mongo plumbing,
// no HTTP surface existed before this ticket.
//
// Thin Controller / Service Layer (project convention since T018's fix to
// UserProfileController): every business rule - participant check, closed-
// chat guard, the history cap - lives in IChatService, not here. This class
// only resolves identity from the gateway-injected header, calls the
// service, and maps its Result Object to an HTTP response.
//
// Real-time (SendMessage) deliberately has NO controller action - see
// ChatHub for that. This controller only exposes the two REST endpoints the
// ticket calls for: recent history and the "Met Successfully" action.
[ApiController]
public class ChatController(IChatService chatService) : ControllerBase
{
    [HttpGet("/chats/{id}/messages")]
    public async Task<IActionResult> GetMessages(string id, [FromQuery] int? limit)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await chatService.GetRecentMessagesAsync(id, userId, limit);

        return outcome.Result switch
        {
            GetMessagesResult.Success => Ok(outcome.Messages),
            GetMessagesResult.ChatRoomNotFound => ChatNotFound(),
            GetMessagesResult.NotAParticipant => NotAParticipant(),
            _ => throw new InvalidOperationException($"Unhandled {nameof(GetMessagesResult)}: {outcome.Result}"),
        };
    }

    [HttpPost("/chats/{id}/met")]
    public async Task<IActionResult> MarkMetSuccessfully(string id)
    {
        if (!HttpContext.TryGetUserId(out var userId))
        {
            return MissingIdentity();
        }

        var outcome = await chatService.MarkMetSuccessfullyAsync(id, userId);

        return outcome.Result switch
        {
            MarkMetResult.Success => Ok(new MarkMetSuccessfullyResponse(id, outcome.BothUsersMet)),
            MarkMetResult.ChatRoomNotFound => ChatNotFound(),
            MarkMetResult.NotAParticipant => NotAParticipant(),
            MarkMetResult.ChatRoomClosed => ChatAlreadyClosed(),
            _ => throw new InvalidOperationException($"Unhandled {nameof(MarkMetResult)}: {outcome.Result}"),
        };
    }

    private IActionResult MissingIdentity() =>
        StatusCode(
            StatusCodes.Status401Unauthorized,
            new ErrorResponse("MISSING_IDENTITY", "X-User-Id header is missing or invalid.", HttpContext.TraceIdentifier));

    private IActionResult ChatNotFound() =>
        StatusCode(
            StatusCodes.Status404NotFound,
            new ErrorResponse("CHAT_ROOM_NOT_FOUND", "No chat room exists with this id.", HttpContext.TraceIdentifier));

    private IActionResult NotAParticipant() =>
        StatusCode(
            StatusCodes.Status403Forbidden,
            new ErrorResponse("NOT_A_PARTICIPANT", "You are not a participant in this chat.", HttpContext.TraceIdentifier));

    private IActionResult ChatAlreadyClosed() =>
        StatusCode(
            StatusCodes.Status409Conflict,
            new ErrorResponse("CHAT_ALREADY_CLOSED", "This chat has already closed.", HttpContext.TraceIdentifier));
}
