package org.rx.lr.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.rx.lr.repository.model.CheckInLog;
import org.rx.lr.repository.model.User;
import org.rx.lr.repository.model.UserComment;
import org.rx.lr.web.dto.user.*;

@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    UserComment toUserComment(SaveUserCommentRequest request);

    @Mapping(source = "id", target = "userCommentId")
    UserCommentResponse toUserCommentResponse(UserComment userComment);

    User toUser(SignUpRequest request);

    @Mapping(source = "id", target = "userId")
    UserResponse toUserResponse(User user);

    CheckInLog toCheckInLog(CheckInRequest request);
}
