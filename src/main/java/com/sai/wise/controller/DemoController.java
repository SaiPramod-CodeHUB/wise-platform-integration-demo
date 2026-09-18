package com.sai.wise.controller;

import com.sai.wise.client.StubWiseClient;
import com.sai.wise.service.TransferStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Demo-only controls, active under the {@code demo} profile.
 *
 * <p>Failure paths are the interesting part of a payments integration and the
 * hardest to show. These endpoints make them reachable on demand, so the
 * compensation path can be demonstrated in thirty seconds rather than described
 * in a paragraph.
 */
@RestController
@RequestMapping("/demo")
@Profile("demo")
public class DemoController {

    private final StubWiseClient stub;
    private final TransferStore store;

    public DemoController(StubWiseClient stub, TransferStore store) {
        this.stub = stub;
        this.store = store;
    }

    @Operation(summary = "Make the next funding call fail, to exercise the CREATED_UNFUNDED compensation path")
    @PostMapping("/fail-next-funding")
    public ResponseEntity<Map<String, Object>> failNextFunding() {
        stub.failNextFunding(true);
        return ResponseEntity.ok(Map.of(
                "armed", true,
                "effect", "The next transfer will be created at Wise but fail to fund.",
                "expect", "503 from /api/v1/transfers, and a CREATED_UNFUNDED record the reconciliation job picks up."));
    }

    @Operation(summary = "Show every transfer this service knows about, and its current state")
    @GetMapping("/state")
    public ResponseEntity<Object> state() {
        return ResponseEntity.ok(Map.of(
                "count", store.size(),
                "transfers", store.findAll(),
                "nonTerminal", store.findNonTerminal()));
    }
}
