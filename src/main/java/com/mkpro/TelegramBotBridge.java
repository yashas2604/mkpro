package com.mkpro;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class TelegramBotBridge implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final Runner runner;
    private final String botToken;
    private final Map<Long, String> chatSessions = new ConcurrentHashMap<>();
    private final ActionLogger logger;
    private final java.util.Set<String> whitelistedUsers;

    public TelegramBotBridge(String botToken, Runner runner, ActionLogger logger) {
        this.botToken = botToken;
        this.runner = runner;
        this.logger = logger;
        this.telegramClient = new OkHttpTelegramClient(botToken);
        
        this.whitelistedUsers = new java.util.HashSet<>();
        String whitelistEnv = System.getenv("TELEGRAM_WHITELISTED_USERS");
        if (whitelistEnv != null && !whitelistEnv.isEmpty()) {
            for (String user : whitelistEnv.split(",")) {
                whitelistedUsers.add(user.trim().toLowerCase());
            }
            System.out.println(MkPro.ANSI_BLUE + "[Security] Telegram Whitelist active: " + whitelistedUsers.size() + " users allowed." + MkPro.ANSI_RESET);
        } else {
            System.out.println(MkPro.ANSI_RED + "[Security] WARNING: No TELEGRAM_WHITELISTED_USERS set. Bot is open to anyone!" + MkPro.ANSI_RESET);
        }
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            String userText = message.getText();
            String username = message.getFrom().getUserName();
            String userId = String.valueOf(message.getFrom().getId());

            // 1. Check Whitelist Security
            if (!whitelistedUsers.isEmpty()) {
                boolean isAuthorized = (username != null && whitelistedUsers.contains(username.toLowerCase())) 
                                    || whitelistedUsers.contains(userId);
                
                if (!isAuthorized) {
                    logger.log("SECURITY", "Unauthorized access attempt from: " + (username != null ? username : "unknown") + " (ID: " + userId + ")");
                    
                    if ("/start".equalsIgnoreCase(userText) || "/id".equalsIgnoreCase(userText)) {
                        sendText(chatId, "🔐 Security active. Your Telegram ID is: " + userId + "\nTo enable access, add this ID to TELEGRAM_WHITELISTED_USERS.");
                    } else {
                        sendText(chatId, "⚠️ Access Denied. This bot is private.");
                    }
                    return;
                }
            }

            // 2. Help / ID command for authorized users
            if ("/id".equalsIgnoreCase(userText)) {
                sendText(chatId, "Your Telegram ID is: " + userId + (username != null ? " (@" + username + ")" : ""));
                return;
            }

            logger.log("TELEGRAM", "[" + (username != null ? username : userId) + "] " + userText);

            // Get or create session for this chat
            String sessionId = chatSessions.computeIfAbsent(chatId, id -> {
                try {
                    String appName = "mkpro-" + System.getProperty("user.name");
                    Session session = runner.sessionService().createSession(appName, "Coordinator").blockingGet();
                    return session.id();
                } catch (Exception e) {
                    return "default";
                }
            });

            // Send "typing" action
            try {
                telegramClient.execute(SendChatAction.builder()
                        .chatId(chatId)
                        .action("typing")
                        .build());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }

            Content content = Content.builder()
                    .role("user")
                    .parts(Collections.singletonList(Part.fromText(userText)))
                    .build();

            StringBuilder responseBuilder = new StringBuilder();
            AtomicReference<Integer> messageIdRef = new AtomicReference<>(null);
            
            // To avoid flooding Telegram API, we'll collect the response and send it.
            // For a more advanced version, we could edit the message periodically to simulate streaming.
            
            runner.runAsync("Coordinator", sessionId, content)
                    .filter(event -> event.content().isPresent())
                    .subscribe(
                            event -> {
                                event.content().flatMap(Content::parts).orElse(Collections.emptyList())
                                        .forEach(part -> part.text().ifPresent(responseBuilder::append));
                                
                                // Optional: Update Telegram message every few chunks for "streaming" feel
                                // For now, we'll just wait for completion to keep it stable.
                            },
                            error -> {
                                sendText(chatId, "Error: " + error.getMessage());
                                logger.log("ERROR", "Telegram error: " + error.getMessage());
                            },
                            () -> {
                                String finalResponse = responseBuilder.toString();
                                if (!finalResponse.isEmpty()) {
                                    sendLongText(chatId, finalResponse);
                                    logger.log("AGENT", "(Telegram) " + finalResponse);
                                }
                            }
                    );
        }
    }

    private void sendText(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendLongText(long chatId, String text) {
        // Telegram has a 4096 character limit per message
        if (text.length() <= 4096) {
            sendText(chatId, text);
        } else {
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + 4096, text.length());
                sendText(chatId, text.substring(start, end));
                start = end;
            }
        }
    }
}
