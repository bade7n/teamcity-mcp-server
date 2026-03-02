package com.example.teamcity.mcp.controller;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTokenAuth;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



@RestController
@RequestMapping(McpSSETransportController.ENDPOINT)
public class McpSSETransportController extends McpController {
  public static final String ENDPOINT = "/mcp/sse/**";

  public McpSSETransportController(@NotNull McpTokenAuth auth,
                                @NotNull McpSseServerTransportProvider transport) {
    super(auth, Mode.SSE, transport);
  }

}
