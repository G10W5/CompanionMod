package com.example.companionmod.build;

/** Decides whether a block id exists. In game: wrap the block registry. In tests: ANY. */
@FunctionalInterface
public interface BlockPolicy {
    boolean isValid(String fullId);

    BlockPolicy ANY = id -> true;
}
