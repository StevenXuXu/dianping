package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送短信验证发成功，验证码{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }

        if(StrUtil.isBlank(loginForm.getPassword()) && StrUtil.isBlank(loginForm.getCode())) {
            return Result.fail("验证码和密码为空");
        }

        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());

        if(StrUtil.isNotBlank(code) && StrUtil.isNotBlank(loginForm.getCode()) && !code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        // 验证密码...

        User user = lambdaQuery().eq(User::getPhone, loginForm.getPhone()).one();

        if(user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((k, v) -> v.toString())));
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok();
        }

        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        UserHolder.removeUser();

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
