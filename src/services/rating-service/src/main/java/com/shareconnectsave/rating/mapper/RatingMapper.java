package com.shareconnectsave.rating.mapper;

import com.shareconnectsave.rating.domain.Rating;
import com.shareconnectsave.rating.domain.RatingResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Pattern: MapStruct generates this implementation at compile time - zero
// reflection overhead, same componentModel = "spring" registration as
// connection-service's ConnectionMapper.
//
// Multiple-source-parameter mapping: RatingResponse.tags/comment have no
// matching property on the Rating entity (Rating.tags is raw NVARCHAR(MAX)
// JSON text, not a List<String>, and Rating has no comment column at all -
// see Rating's own class comment), so they are supplied as EXTRA method
// parameters named identically to the target properties (tags, comment)
// rather than derived from the entity. MapStruct resolves the ambiguity
// between the `tags` parameter (List<String>) and Rating.getTags()
// (String) by type - only the List<String> parameter can satisfy a
// List<String> target, so no explicit @Mapping is needed for it; comment
// has no entity-side candidate at all. id/connectionId/raterId/ratedId/
// createdAt all match Rating's own property names exactly and are wired
// automatically.
@Mapper(componentModel = "spring")
public interface RatingMapper {

    @Mapping(target = "connectionId", source = "rating.connectionId")
    @Mapping(target = "raterId", source = "rating.raterId")
    @Mapping(target = "ratedId", source = "rating.ratedId")
    @Mapping(target = "createdAt", source = "rating.createdAt")
    // Explicit source = "tags": without this, MapStruct's automatic
    // same-name matching favors Rating.getTags() (a plain property on the
    // first parameter) over the dedicated `tags` parameter below, then
    // fails to compile because a raw JSON String can't become a
    // List<String> implicitly. Naming the parameter here explicitly
    // resolves the ambiguity in the parameter's favor.
    @Mapping(target = "tags", source = "tags")
    RatingResponse toResponse(Rating rating, List<String> tags, String comment);
}
