package org.rx.lr.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.rx.lr.repository.model.User;
import org.rx.lr.web.dto.user.SignUpRequest;

@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    User toUser(SignUpRequest request);
}
