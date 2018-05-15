package org.rx.lr.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.rx.lr.repository.model.Article;
import org.rx.lr.repository.model.ArticleType;
import org.rx.lr.web.dto.article.ArticleResponse;
import org.rx.lr.web.dto.article.ArticleTypeResponse;
import org.rx.lr.web.dto.article.SaveArticleRequest;
import org.rx.lr.web.dto.article.SaveArticleTypeRequest;

@Mapper
public interface ArticleMapper {
    ArticleMapper INSTANCE = Mappers.getMapper(ArticleMapper.class);

    @Mapping(source = "articleTypeId", target = "id")
    ArticleType toArticleType(SaveArticleTypeRequest request);

    @Mapping(source = "id", target = "articleTypeId")
    ArticleTypeResponse toArticleTypeResponse(ArticleType articleType);

    @Mapping(source = "articleId", target = "id")
    Article toArticle(SaveArticleRequest request);

    @Mapping(source = "id", target = "articleId")
    ArticleResponse toArticleResponse(Article article);
}
