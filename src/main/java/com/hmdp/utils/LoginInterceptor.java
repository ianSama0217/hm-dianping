package com.hmdp.utils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.web.servlet.HandlerInterceptor;

import com.hmdp.entity.User;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) throws Exception {
        // 取得 session
        HttpSession session = request.getSession();

        // 取得 session 中的用戶
        Object user = session.getAttribute("user");

        // 判斷用戶是否存在
        if (user == null) {
            // 未登入，返回 401 狀態碼
            response.setStatus(401);
            return false;
        }

        // 已登入，將用戶信息存入 ThreadLocal
        UserHolder.saveUser((User) user);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
            HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用戶
        UserHolder.removeUser();
    }
}
