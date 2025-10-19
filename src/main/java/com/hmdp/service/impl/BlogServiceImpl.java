package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            // 判斷是否被點讚
            this.isBlogLiked(blog);

        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查詢貼文
        Blog blog = getById(id);

        if (blog == null) {
            return Result.fail("貼文不存在");
        }

        // 查詢blog發文用戶
        queryBlogUser(blog);

        // 查詢blog是否被點讚
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 取得登錄用戶
        Long userId = UserHolder.getUser().getId();

        // 判斷是否點讚
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 沒點讚, 點讚數+1, 加到redis sorted set
            boolean isSeccuss = update().setSql("liked = liked + 1").eq("id", id).update();

            if (isSeccuss) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }

        } else {
            // 已點讚, 點讚數-1, 從redis sorted set移除
            boolean isSeccuss = update().setSql("liked = liked - 1").eq("id", id).update();

            if (isSeccuss) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 查詢top5的點讚用戶
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }

        // 查詢其中的user id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        // 根據user id查詢用戶
        String idStr = StrUtil.join(",", ids);

        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        // 取得登錄用戶
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return; // 未登錄, 不須判斷
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 取得登入用戶
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店貼文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增貼文失敗");
        }
        // 查詢貼文作者的所有粉絲
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送貼文給所有粉絲
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

}
