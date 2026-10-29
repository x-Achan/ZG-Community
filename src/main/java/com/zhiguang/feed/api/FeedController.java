package com.zhiguang.feed.api;

import com.zhiguang.auth.token.JwtService;
import com.zhiguang.feed.api.dto.FeedPageResponse;
import com.zhiguang.feed.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowposts")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;
    private final JwtService jwtService;

    /**
     * 热门首页，允许匿名。
     */
    @GetMapping("/feed")
    public FeedPageResponse hot(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {

        // 用户未登录，是可以访问热门Feed的
        Long viewerId = jwt == null ? null : jwtService.extractUserId(jwt);
        return feedService.hot(cursor, size, viewerId);
    }

    /**
     * 我的主页，包含草稿，不包含 deleted。
     */
    @GetMapping("/feed/mine")
    public FeedPageResponse mine(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {

        long viewerId = jwtService.extractUserId(jwt);
        return feedService.mine(viewerId, cursor, size);
    }

    /**
     * 其他用户主页。
     *
     * 未关注：public
     * 已关注：public + followers
     */
    @GetMapping("/feed/users/{authorId}")
    public FeedPageResponse profile(
            @PathVariable long authorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {

        long viewerId = jwtService.extractUserId(jwt);
        return feedService.profile(authorId, viewerId, cursor, size);
    }

    /**
     * 关注 Feed。
     */
    @GetMapping("/feed/following")
    public FeedPageResponse following(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {

        long viewerId = jwtService.extractUserId(jwt);
        return feedService.following(viewerId, cursor, size);
    }
}