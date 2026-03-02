package com.example.teamcity.mcp.controller;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTokenAuth;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;


@RestController
@RequestMapping(McpSSETransportController.ENDPOINT)
public class McpSSETransportController {
  public static final String ENDPOINT = "/mcp/sse";

  private final McpSseServerTransportProvider transport;


  public McpSSETransportController(@NotNull McpSseServerTransportProvider transport) {
    this.transport = transport;
    System.out.println("McpSSEMessageController being registered/started");
  }


  @RequestMapping(method = RequestMethod.GET)
  public ModelAndView handle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(405);
      return null;
    }
    transport.handleSse(request, response);
    return null;
  }
}
