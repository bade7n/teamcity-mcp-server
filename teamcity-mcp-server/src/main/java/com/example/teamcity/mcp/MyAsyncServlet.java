package com.example.teamcity.mcp;

import java.io.IOException;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class MyAsyncServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {

        // Запуск асинхронного контекста
        AsyncContext asyncContext = req.startAsync();

        asyncContext.start(() -> {
            try {
                // Имитация долгой работы
                Thread.sleep(3000);

                HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
                response.getWriter().write("Async response!");

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                asyncContext.complete(); // обязательно завершить
            }
        });
    }
}