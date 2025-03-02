package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import cn.hutool.core.util.RandomUtil;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

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
}
