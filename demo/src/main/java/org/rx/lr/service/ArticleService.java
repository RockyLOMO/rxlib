package org.rx.lr.service;

import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.NQuery;
import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.Article;
import org.rx.lr.repository.model.ArticleType;
import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.service.mapper.ArticleMapper;
import org.rx.lr.web.dto.article.*;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.rx.Contract.eq;

@EnableValid
@Service
public class ArticleService {
    @Autowired
    private IRepository<ArticleType> articleTypeIRepository;
    @Autowired
    private IRepository<Article> articleIRepository;

    public ArticleTypeResponse saveArticleType(SaveArticleTypeRequest request) {
        if (request.isDeleting() && request.getArticleTypeId() != null) {
            if (articleIRepository.count(p -> p.getTypeId().equals(request.getArticleTypeId())) > 0) {
                throw new InvalidOperationException("该类别下还有新闻，请先删除新闻");
            }
            if (articleTypeIRepository.count(p -> eq(p.getParentId(), request.getArticleTypeId())) > 0) {
                throw new InvalidOperationException("该类别下子类，请先删除子类");
            }

            return ArticleMapper.INSTANCE.toArticleTypeResponse(articleTypeIRepository.delete(request.getArticleTypeId()));
        }

        ArticleType articleType = ArticleMapper.INSTANCE.toArticleType(request);
        if (articleType.getParentId() != null) {
            if (articleTypeIRepository.count(p -> p.getId().equals(articleType.getParentId())) == 0) {
                throw new InvalidOperationException("父类不存在");
            }
        }

        articleTypeIRepository.save(articleType);
        return ArticleMapper.INSTANCE.toArticleTypeResponse(articleType);
    }

    public List<ArticleTypeResponse> queryArticleTypes(QueryArticleTypesRequest request) {
        return NQuery.of(articleTypeIRepository.list(p -> (request.getArticleTypeId() == null || p.getId().equals(request.getArticleTypeId()))
                && (request.getName() == null || p.getName().contains(request.getName())), p -> p.getCreateTime()))
                .select(p -> ArticleMapper.INSTANCE.toArticleTypeResponse(p)).toList();
    }

    public ArticleResponse saveArticle(SaveArticleRequest request) {
        if (articleTypeIRepository.count(p -> p.getId().equals(request.getTypeId())) == 0) {
            throw new InvalidOperationException("类别不存在");
        }

        Article article = ArticleMapper.INSTANCE.toArticle(request);
        articleIRepository.save(article);
        return ArticleMapper.INSTANCE.toArticleResponse(article);
    }

    public PagedResult<ArticleResponse> queryArticles(QueryArticlesRequest request) {
        return articleIRepository.pageDescending(p -> (request.getArticleId() == null || p.getId().equals(request.getArticleId()))
                && (request.getTitle() == null || p.getTitle().contains(request.getTitle())
                && (App.isNullOrEmpty(request.getTypeIds()) || request.getTypeIds().contains(p.getTypeId()))), p -> p.getCreateTime(), request)
                .convert(p -> ArticleMapper.INSTANCE.toArticleResponse(p));
    }
}
