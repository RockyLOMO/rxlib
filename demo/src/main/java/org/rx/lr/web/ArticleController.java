package org.rx.lr.web;

import org.rx.lr.service.ArticleService;
import org.rx.lr.web.dto.article.*;
import org.rx.repository.PagedResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/article", method = RequestMethod.POST)
public class ArticleController {
    @Autowired
    private ArticleService articleService;

    @RequestMapping(value = "/saveArticleType")
    public ArticleTypeResponse saveArticleType(@RequestBody SaveArticleTypeRequest request) {
        return articleService.saveArticleType(request);
    }

    @RequestMapping(value = "/queryArticleTypes")
    public List<ArticleTypeResponse> queryArticleTypes(@RequestBody QueryArticleTypesRequest request) {
        return articleService.queryArticleTypes(request);
    }

    @RequestMapping(value = "/saveArticle")
    public ArticleResponse saveArticle(SaveArticleRequest request) {
        return articleService.saveArticle(request);
    }

    @RequestMapping(value = "/queryArticles")
    public PagedResult<ArticleResponse> queryArticles(QueryArticlesRequest request) {
        return articleService.queryArticles(request);
    }
}
