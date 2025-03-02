package com.hmdp.service.impl;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import cn.hutool.core.util.RandomUtil;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合, 返回错误信息
            return Result.fail("手机号格式错误!");
        }

        // 符合, 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到 session
        session.setAttribute("code", code);

        // (模拟) 发送验证码
        log.debug("发送短信验证码成功, 验证码: {}", code);

        // 返回 ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误!");
        }

        // 校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();

        // 不一致, 报错
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }

        // 一致, 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 判断用户是否存在
        if (user == null) {
            // 不存在, 创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 保存用户信息到 session 中
        session.setAttribute("user", user);

        // 返回 ok
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        
        // 保存用户
        save(user);
        return null;
    }
}
