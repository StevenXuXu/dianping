package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                String key = "follows:" + userId;
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            LambdaQueryWrapper<Follow> lambdaQueryWrapper = Wrappers.lambdaQuery(Follow.class).eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id);
            boolean isSuccess = remove(lambdaQueryWrapper);
            if (isSuccess) {
                String key = "follows:" + userId;
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if(user == null) return Result.ok(false);
        Long userId = user.getId();
        int count = count(Wrappers.<Follow>lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = ids.stream().map(nowId -> BeanUtil.copyProperties(userService.getById(nowId), UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
