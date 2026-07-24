package com.yubai.blog.sitemap;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RobotsController {

    private final RobotsService robotsService;

    public RobotsController(RobotsService robotsService) {
        this.robotsService = robotsService;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String robots() {
        return robotsService.buildRobotsTxt();
    }
}
