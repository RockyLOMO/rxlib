package org.rx.juan_zhai.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.rx.juan_zhai.repository.model.CheckInLog;
import org.rx.juan_zhai.repository.model.User;
import org.rx.juan_zhai.repository.model.UserComment;
import org.rx.juan_zhai.web.dto.user.*;

@Mapper
public interface JzUserMapper {
    JzUserMapper INSTANCE = Mappers.getMapper(JzUserMapper.class);

    UserComment toUserComment(SaveUserCommentRequest request);

    @Mapping(source = "id", target = "userCommentId")
    UserCommentResponse toUserCommentResponse(UserComment userComment);

    User toUser(SignUpRequest request);

    @Mapping(source = "id", target = "userId")
    UserResponse toUserResponse(User user);

    CheckInLog toCheckInLog(CheckInRequest request);
}
