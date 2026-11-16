package io.scent.advisor;

public final class AdvisorModels {
    private AdvisorModels() { }
    public record Input(String conversationId, String requestId, int turn, String message) { }
    public record Reply(String message) { }
}
