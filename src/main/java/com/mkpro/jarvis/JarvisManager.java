package com.mkpro.jarvis;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.AsyncSession;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.mkpro.audio.Microphone;
import com.mkpro.audio.Speaker;
import com.mkpro.jarvis.core.AgentExecutionEvent;
import com.mkpro.jarvis.core.AsyncEventBus;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.Collections;

public class JarvisManager {
    private final Client client;
    private final Microphone microphone;
    private final Speaker speaker;
    private final Runner mkproRunner;
    private final String sessionId;
    private final AsyncEventBus eventBus;

    private AsyncSession mainSession;
    private volatile boolean isRunning = false;
    private Disposable agentSubscription;
    private volatile long lastUpdateMs = 0L;
    private volatile String lastUpdateText = "";

    public JarvisManager(Runner runner, String sessionId) {
        this.client = Client.builder().build(); // Uses GEMINI_API_KEY env var
        this.microphone = new Microphone(16000);
        this.speaker = new Speaker(24000); // Gemini returns 24kHz
        this.mkproRunner = runner;
        this.sessionId = sessionId;
        this.eventBus = new AsyncEventBus();

        // Subscribe to agent execution events to narrate them
        this.eventBus.subscribe(AgentExecutionEvent.class, event -> sendSystemUpdate(event.getMessage()));
    }

    public void start() throws Exception {
        isRunning = true;
        speaker.start();

        LiveConnectConfig mainConfig = LiveConnectConfig.builder()
                .responseModalities(Collections.singletonList(new Modality(Modality.Known.AUDIO)))
                .speechConfig(SpeechConfig.builder()
                        .voiceConfig(VoiceConfig.builder()
                                .prebuiltVoiceConfig(
                                        PrebuiltVoiceConfig.builder()
                                                .voiceName("Aoede")
                                                .build()
                                )
                                .build())
                        .build())
                .inputAudioTranscription(AudioTranscriptionConfig.builder().build())
                .outputAudioTranscription(AudioTranscriptionConfig.builder().build())
                .realtimeInputConfig(RealtimeInputConfig.builder()
                        .turnCoverage(TurnCoverage.Known.TURN_INCLUDES_ONLY_ACTIVITY)
                        .automaticActivityDetection(AutomaticActivityDetection.builder()
                                .startOfSpeechSensitivity(StartSensitivity.Known.START_SENSITIVITY_HIGH)
                                .endOfSpeechSensitivity(EndSensitivity.Known.END_SENSITIVITY_HIGH)
                                .silenceDurationMs(400)
                                .build())
                        .build())
                .tools(Collections.singletonList(Tool.builder()
                        .functionDeclarations(Collections.singletonList(FunctionDeclaration.builder()
                                .name("run_mkpro_task")
                                .description("Executes a task using the MkPro agent framework. Use this only for concrete codebase tasks.")
                                .parameters(Schema.builder()
                                        .type("OBJECT")
                                        .properties(Collections.singletonMap("prompt", Schema.builder()
                                                .type("STRING")
                                                .description("The full, exact task description or question from the user")
                                                .build()))
                                        .required(Collections.singletonList("prompt"))
                                        .build())
                                .build()))
                        .build()))
                .systemInstruction(Content.builder().parts(Collections.singletonList(Part.builder().text(
                        "You are Jarvis, the voice interface for the MkPro agent framework.\n" +
                        "ROLE:\n" +
                        "1. You are allowed to think and respond normally for general conversation.\n" +
                        "2. Only when the user asks you to do a concrete task in the codebase (read/write files, run commands, change code, analyze project files), you MUST call `run_mkpro_task` with the user's exact request.\n" +
                        "3. If it's unclear whether a request is a task, ask a brief clarifying question.\n" +
                        "4. While a task is running, you will receive text inputs starting with 'System Update:'. These are real-time updates about what the backend agents are doing.\n" +
                        "5. When you receive a 'System Update:', briefly narrate it to the user in a natural, conversational, slightly witty way. Keep it to 1 short sentence.\n" +
                        "6. When `run_mkpro_task` returns a result, summarize it concisely for the user. Be direct and honest."
                ).build())).build())
                .build();

        mainSession = client.async.live.connect("gemini-3.1-flash-live-preview", mainConfig).get();
        mainSession.receive(this::handleMainMessage);
        sendSystemUpdate("Greet the user and say you are ready to take requests.");

        microphone.start(data -> {
            if (isRunning && mainSession != null) {
                mainSession.sendRealtimeInput(LiveSendRealtimeInputParameters.builder()
                        .audio(Blob.builder().mimeType("audio/pcm;rate=16000").data(data).build())
                        .build());
            }
        });

        System.out.println("Jarvis is listening... (Press Enter to stop)");
    }

    private void handleMainMessage(LiveServerMessage message) {
        try {
            if (message.serverContent().isPresent()
                    && message.serverContent().get().modelTurn().isPresent()
                    && message.serverContent().get().modelTurn().get().parts().isPresent()) {
                for (Part part : message.serverContent().get().modelTurn().get().parts().get()) {
                    if (part.inlineData().isPresent() && part.inlineData().get().data().isPresent()) {
                        byte[] audioData = part.inlineData().get().data().get();
                        speaker.play(audioData);
                    }
                    part.text().ifPresent(text -> System.out.println("[Jarvis/Text] " + text));
                }
            }

            if (message.toolCall().isPresent() && message.toolCall().get().functionCalls().isPresent()) {
                for (FunctionCall call : message.toolCall().get().functionCalls().get()) {
                    if ("run_mkpro_task".equals(call.name())) {
                        String prompt = (String) call.args().get().get("prompt");
                        new Thread(() -> executeMkproTask(call.id().orElse(""), prompt), "Jarvis-Task-Thread").start();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Jarvis/Error] handleMainMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void executeMkproTask(String callId, String prompt) {
        System.out.println("\n[Jarvis] Executing task: " + prompt);
        eventBus.publish(new AgentExecutionEvent("Started task: " + prompt));

        com.google.adk.agents.RunConfig runConfig = com.google.adk.agents.RunConfig.builder()
                .setStreamingMode(com.google.adk.agents.RunConfig.StreamingMode.SSE)
                .build();

        Content promptContent = Content.builder()
                .parts(Collections.singletonList(Part.builder().text(prompt).build()))
                .build();
        Flowable<Event> flowable = mkproRunner.runAsync("Coordinator", sessionId, promptContent, runConfig);
        StringBuilder finalResponse = new StringBuilder();

        agentSubscription = flowable.subscribe(
                event -> {
                    if (event.content().isPresent() && event.content().get().parts().isPresent()) {
                        event.content().get().parts().get().forEach(part ->
                                part.text().ifPresent(finalResponse::append));
                    }

                    if (!event.functionCalls().isEmpty()) {
                        FunctionCall fc = event.functionCalls().get(0);
                        String argsStr = fc.args() != null ? fc.args().toString() : "";
                        if (argsStr.length() > 50) argsStr = argsStr.substring(0, 50) + "...";
                        String narrationText = "Action update: Agent is using tool '" + fc.name() + "' with args: " + argsStr;
                        System.out.println("[Narration] " + narrationText);
                        eventBus.publish(new AgentExecutionEvent(narrationText));
                    }
                },
                error -> {
                    System.err.println("Error executing task: " + error.getMessage());
                    eventBus.publish(new AgentExecutionEvent("Task failed: " + error.getMessage()));
                    sendFunctionResponse(callId, "Error: " + error.getMessage());
                },
                () -> {
                    System.out.println("[Jarvis] Task complete.");
                    eventBus.publish(new AgentExecutionEvent("Task complete."));
                    sendFunctionResponse(callId, finalResponse.toString());
                }
        );
    }

    private void sendSystemUpdate(String text) {
        if (mainSession != null) {
            String update = "System Update: " + text;
            long now = System.currentTimeMillis();
            if (update.equals(lastUpdateText) || now - lastUpdateMs < 600) {
                return;
            }
            lastUpdateMs = now;
            lastUpdateText = update;
            mainSession.sendRealtimeInput(LiveSendRealtimeInputParameters.builder().text(update).build());
        }
    }

    private void sendFunctionResponse(String callId, String result) {
        if (mainSession != null) {
            FunctionResponse fr = FunctionResponse.builder()
                    .id(callId)
                    .name("run_mkpro_task")
                    .response(Collections.singletonMap("result", result))
                    .build();

            LiveSendToolResponseParameters params = LiveSendToolResponseParameters.builder()
                    .functionResponses(Collections.singletonList(fr))
                    .build();
            mainSession.sendToolResponse(params);
        }
    }

    public void stop() {
        isRunning = false;
        microphone.stop();
        speaker.stop();
        eventBus.shutdown();
        if (agentSubscription != null && !agentSubscription.isDisposed()) {
            agentSubscription.dispose();
        }
    }
}
