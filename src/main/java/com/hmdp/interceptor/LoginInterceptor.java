package com.hmdp.interceptor;

import org.springframework.web.servlet.HandlerInterceptor;
import com.hmdp.utils.UserHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/*
 * 拦截需要登录的请求
 * 如果用户已经登录, RefreshTokenInterceptor 已将用户信息保存到 ThreadLocal
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断 ThreadLocal 中是否有用户
        if (UserHolder.getUser() == null) {
            // 不存在, 拦截, 设置状态码
            response.setStatus(401);
            return false;
        }
        // 放行
        return true;
    }
}
