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

import static com.example.teamcity.mcp.controller.McpMessageController.ENDPOINT;

@RestController
@RequestMapping(ENDPOINT)
public class McpMessageController {
  public static final String ENDPOINT = "/mcp/message";

  private final McpSseServerTransportProvider transport;

  public McpMessageController(@NotNull McpSseServerTransportProvider transport) {
    this.transport = transport;
    System.out.println("McpMessageController being registered/started");
  }

  @RequestMapping(method = RequestMethod.POST)
  public ModelAndView handle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    transport.handleMessage(request, response);
    return null;
  }
}
