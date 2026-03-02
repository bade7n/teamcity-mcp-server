package com.example.teamcity.mcp.controller;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTokenAuth;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.example.teamcity.mcp.controller.McpMessageController.ENDPOINT;

@RestController
@RequestMapping(ENDPOINT)
public class McpMessageController extends McpController {
  public static final String ENDPOINT = "/mcp/message/**";

  public McpMessageController(@NotNull McpTokenAuth auth,
                              @NotNull McpSseServerTransportProvider transport) {
    super(auth, Mode.MESSAGE, transport);
  }

}
