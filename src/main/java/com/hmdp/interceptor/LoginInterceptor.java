package com.hmdp.interceptor;

import org.springframework.web.servlet.HandlerInterceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取 session
        HttpSession session = request.getSession();

        // 获取 session 中的用户
        Object user = session.getAttribute("user");

        // 判断用户是否存在
        if (user == null) {
            // 不存在, 拦截, 返回 401
            response.setStatus(401);
            return false;
        }

        // 存在, 保存用户信息到 ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除线程绑定的用户
        UserHolder.removeUser();
    }
}
