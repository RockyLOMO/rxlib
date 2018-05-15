package org.rx.lr.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.rx.lr.repository.model.CheckInLog;
import org.rx.lr.repository.model.User;
import org.rx.lr.web.dto.user.CheckInRequest;
import org.rx.lr.web.dto.user.SignUpRequest;
import org.rx.lr.web.dto.user.UserResponse;

@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    User toUser(SignUpRequest request);

    @Mapping(source = "id", target = "userId")
    UserResponse toUserResponse(User user);

    CheckInLog toCheckInLog(CheckInRequest request);
}
