package com.example.repro;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkController {

  @GetMapping("/api/work")
  public Map<String, Object> work() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("message", "business endpoint");
    response.put("now", Instant.now().toString());
    return response;
  }
}
