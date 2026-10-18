package com.stayhotel.payment.sandbox;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The simulated provider's hosted checkout page. Plain HTML forms (no JavaScript, CSP-friendly). Clearly labelled as
 * a sandbox: no card data is requested or stored anywhere.
 */
@RestController
@RequestMapping("/sandbox-gateway/checkout")
class SandboxCheckoutController {

    private static final Pattern SESSION_ID = Pattern.compile("^cs_[0-9a-f]{24}$");

    private final SandboxGateway gateway;

    SandboxCheckoutController(SandboxGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return notFound();
        }
        return gateway.find(sessionId).map(s -> {
            String amount = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(s.amount()) + " " + s.currency();
            String body = "OPEN".equals(s.status()) ? """
                    <p>Amount to pay</p><p class="amount">%s</p>
                    <form method="post" action="/sandbox-gateway/checkout/%s/pay"><button class="pay" type="submit">Pay (simulate success)</button></form>
                    <form method="post" action="/sandbox-gateway/checkout/%s/decline"><button class="decline" type="submit">Decline (simulate failure)</button></form>
                    """.formatted(HtmlUtils.htmlEscape(amount), s.id(), s.id())
                    : "<p>This checkout session is already " + HtmlUtils.htmlEscape(s.status().toLowerCase(Locale.ROOT))
                    + ".</p><p><a href=\"" + HtmlUtils.htmlEscape(s.returnUrl()) + "\">Return to the merchant</a></p>";
            return html(HttpStatus.OK, body);
        }).orElseGet(SandboxCheckoutController::notFound);
    }

    @PostMapping("/{sessionId}/pay")
    ResponseEntity<String> pay(@PathVariable String sessionId) {
        return finish(sessionId, true);
    }

    @PostMapping("/{sessionId}/decline")
    ResponseEntity<String> decline(@PathVariable String sessionId) {
        return finish(sessionId, false);
    }

    /** The webhook is sent before redirecting; the merchant must still verify it rather than trust the redirect. */
    private ResponseEntity<String> finish(String sessionId, boolean paid) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return notFound();
        }
        return gateway.complete(sessionId, paid)
                .map(returnUrl -> ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, returnUrl)
                        .<String>build())
                .orElseGet(() -> html(HttpStatus.CONFLICT, "<p>This checkout session was already completed.</p>"));
    }

    private static ResponseEntity<String> notFound() {
        return html(HttpStatus.NOT_FOUND, "<p>Unknown checkout session.</p>");
    }

    private static ResponseEntity<String> html(HttpStatus status, String content) {
        String page = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Sandbox Pay</title><style>
                body{font-family:system-ui,sans-serif;background:#f4f1fb;margin:0;display:grid;place-items:center;min-height:100vh}
                main{background:#fff;padding:32px;border-radius:12px;max-width:380px;width:calc(100%% - 32px);box-shadow:0 4px 24px #0001}
                .badge{background:#ffe8a3;color:#7a4b00;padding:4px 10px;border-radius:999px;font-size:.8rem}
                .amount{font-size:1.8rem;font-weight:700;margin:4px 0 16px}button{width:100%%;padding:12px;margin:6px 0;border:0;border-radius:8px;font-size:1rem;cursor:pointer}
                .pay{background:#5b3cc4;color:#fff}.decline{background:#eee}</style></head>
                <body><main><span class="badge">SANDBOX — no real money</span><h1>Sandbox Pay</h1>%s
                <p style="font-size:.8rem;color:#666">Simulated payment provider for development. No card details are collected.</p></main></body></html>
                """.formatted(content);
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
    }
}
