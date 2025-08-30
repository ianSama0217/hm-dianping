package com.hmdp.utils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) throws Exception {
        // 判斷用戶是否存在 (ThreadLocal中是否有用戶)
        if (UserHolder.getUser() == null) {
            // 未登入，返回 401 狀態碼
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
