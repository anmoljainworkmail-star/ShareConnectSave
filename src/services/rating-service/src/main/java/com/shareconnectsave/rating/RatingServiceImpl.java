package com.shareconnectsave.rating;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shareconnectsave.rating.chat.ChatClosureChecker;
import com.shareconnectsave.rating.domain.Rating;
import com.shareconnectsave.rating.domain.RatingResponse;
import com.shareconnectsave.rating.domain.RatingSubmissionDto;
import com.shareconnectsave.rating.domain.RatingSubmittedEvent;
import com.shareconnectsave.rating.domain.RatingTag;
import com.shareconnectsave.rating.exception.ChatNotClosedException;
import com.shareconnectsave.rating.exception.DuplicateRatingException;
import com.shareconnectsave.rating.exception.SelfRatingException;
import com.shareconnectsave.rating.mapper.RatingMapper;
import com.shareconnectsave.rating.outbox.IOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Single Responsibility (SOLID-S): every validation rule, duplicate check,
// and exception decision for submitting a rating lives HERE -
// RatingController only translates HTTP <-> DTOs and never contains an
// if/else of its own. The pure trust-score math (what score results from
// these tags) is deliberately NOT here - that is T043's TrustScoreCalculator
// job; this class only persists the rating and announces that it happened.
//
// Dependency Inversion (SOLID-D): every collaborator below is an interface
// (RatingRepository, IOutboxService, ChatClosureChecker) or a MapStruct-
// generated bean (RatingMapper) - none of them care whether the closed-chat
// check is a placeholder today or a real Kafka-sourced cache later, and a
// unit test of this class can substitute any of them with no Spring context
// at all.
@Service
@RequiredArgsConstructor
public class RatingServiceImpl implements RatingService {

    private final RatingRepository ratingRepository;
    private final IOutboxService outboxService;
    private final ChatClosureChecker chatClosureChecker;
    private final RatingMapper ratingMapper;
    private final ObjectMapper objectMapper;

    // @Transactional: the duplicate-check read, the ratings INSERT, and the
    // outbox row for rating.submitted must all be seen by the DB as one
    // unit of work - Outbox Pattern - so a crash between "save the rating"
    // and "publish the event" can never leave a rating with no event ever
    // published. Guard clauses run first and in the ticket's specified
    // order (self-rating, duplicate, tag validity, chat-closed), each
    // throwing and returning early rather than nesting if/else (per user
    // preference).
    @Override
    @Transactional
    public RatingResponse submitRating(Long raterId, RatingSubmissionDto dto) {
        // Guard 1: no self-ratings. Checked first and without touching the
        // DB at all - a user rating themselves is never valid regardless of
        // any other state.
        if (raterId.equals(dto.ratedId())) {
            throw new SelfRatingException(raterId);
        }

        // Guard 2: duplicate rating. V001's unique index
        // (idx_ratings_connection_id_rater_id) is the last line of defense
        // against a genuine race between two concurrent requests; this
        // existence check is what lets the common case fail fast with a
        // clean 409 instead of relying on a DataIntegrityViolationException
        // handler for every duplicate.
        if (ratingRepository.existsByConnectionIdAndRaterId(dto.connectionId(), raterId)) {
            throw new DuplicateRatingException(dto.connectionId(), raterId);
        }

        // Guard 3: tag validity (Open/Closed, SOLID-O). Each raw wire
        // string is checked against the RatingTag enum via fromWireValue -
        // an unrecognized tag throws InvalidRatingTagException immediately,
        // before anything is persisted.
        List<RatingTag> tags = dto.tags().stream()
                .map(RatingTag::fromWireValue)
                .toList();

        // Guard 4: the connection's chat must be closed. See
        // ChatClosureChecker's own class comment for the documented,
        // accepted T042/T044 sequencing gap this check currently sits on
        // top of.
        if (!chatClosureChecker.isChatClosed(dto.connectionId())) {
            throw new ChatNotClosedException(dto.connectionId());
        }

        Rating rating = Rating.builder()
                .raterId(raterId)
                .ratedId(dto.ratedId())
                .connectionId(dto.connectionId())
                .tags(serializeTags(tags))
                .build();
        // createdAt intentionally left null on this builder - the column is
        // insertable = false on Rating, so SQL Server's own
        // SYSUTCDATETIME() default fills it in at INSERT time.
        Rating saved = ratingRepository.save(rating);

        // Outbox Pattern: this event's write and the INSERT above commit
        // together inside this method's @Transactional boundary.
        // Event-Driven Architecture: this endpoint never calls a trust
        // score calculator synchronously - it only states the fact that a
        // rating was submitted; T043's consumer (and later tickets) react
        // to this event independently, on their own schedule.
        String eventId = UUID.randomUUID().toString();
        RatingSubmittedEvent event = new RatingSubmittedEvent(
                eventId,
                saved.getId().toString(),
                raterId.toString(),
                dto.ratedId().toString(),
                tags
        );
        // Partition key = rated_id (the user whose trust score will be
        // recalculated), ensuring all ratings against the same user land in
        // the same partition for ordered processing by a single consumer
        // thread — matching this platform's "partition by the aggregate"
        // convention applied to the scoring entity, not the rating.
        outboxService.publish("rating.submitted", dto.ratedId().toString(), eventId, event);

        List<String> wireTags = tags.stream().map(RatingTag::toWireValue).toList();
        return ratingMapper.toResponse(saved, wireTags, dto.comment());
    }

    private String serializeTags(List<RatingTag> tags) {
        try {
            // RatingTag's own @JsonValue is what makes this produce the
            // contract's Title Case wire strings ("On time", ...) rather
            // than the Java enum constant names - see RatingTag's class
            // comment.
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            // A List<RatingTag> that fails to serialize is a programmer
            // error (RatingTag itself is malformed), not a transient
            // failure - fail loudly rather than silently dropping tags.
            throw new IllegalStateException("Failed to serialize rating tags", e);
        }
    }
}
