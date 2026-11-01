package com.example.companionmod.voice;

/** One validated step of an AI-made plan. {@code seconds} is the hold time / timeout for the step. */
public record PlanStep(String op, String arg, int seconds) {}
