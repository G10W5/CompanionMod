package com.example.companionmod.command;

import java.util.Locale;

/** Everything the player can order the companion to do. Minecraft-free so the voice layer can use it. */
public enum CompanionCommand {
    NONE, FOLLOW, STAY, SIT, STAND, ATTACK, GUARD, DEFENSIVE, PASSIVE, COME, STOP, CONTINUE,
    INVENTORY, MINE, BUILD, FARM, PATROL, HEAL, EQUIP, TRADE, LOOT, SCOUT,
    WANDER, EXPLORE, DEFEND, RETREAT, WAIT, SNEAK, JUMP, DANCE, SLEEP, WAKE,
    GIVE, TAKE, DROP, PICKUP, OPEN, CLOSE, CRAFT, SMELT, BREW, ENCHANT,
    FISH, COOK, FIGHT, FLEE, HIDE, SEEK, FIND, SEARCH, TRACK, HUNT,
    SEEK_BLOCK, SET_NAME, REVIVE, PLAN,
    REMEMBER, FORGET, SET_SKIN, FEMININE, MASCULINE, PLANT;

    /** False for appearance/UI/memory/revive packets: they are not a new gameplay intent and must not pause or cancel a plan. */
    public boolean interruptsPlanning() {
        return switch (this) {
            case NONE, PLAN, CONTINUE, INVENTORY, SET_NAME, SET_SKIN, FEMININE, MASCULINE,
                    REMEMBER, FORGET, REVIVE -> false;
            default -> true;
        };
    }

    /** Lenient parser: accepts the enum name plus common synonyms the model might produce. */
    public static CompanionCommand parse(String raw) {
        if (raw == null) return NONE;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (s) {
            case "follow": case "follow_me": case "come_along": return FOLLOW;
            case "stay": case "wait": case "hold": case "hold_position": case "stand_ground": return STAY;
            case "sit": case "rest": case "sit_down": return SIT;
            case "stand": case "stand_up": case "get_up": return STAND;
            case "attack": case "kill": case "engage": return ATTACK;
            case "guard": case "protect": case "aggressive": return GUARD;
            case "defensive": case "defend": case "defend_me": case "protect_me": return DEFENSIVE;
            case "passive": case "peaceful": case "dont_fight": case "stand_down": return PASSIVE;
            case "come": case "come_here": case "teleport": case "here": return COME;
            case "stop": case "cancel": case "cease": case "abort": case "halt":
            case "nevermind": case "never_mind": case "stop_everything": case "stop_working":
            case "stop_mining": case "cancel_task": case "cancel_plan": case "stop_attacking": return STOP;
            case "continue": case "resume": case "keep_going": case "carry_on": case "unpause": return CONTINUE;
            case "inventory": case "inv": case "items": case "bag": case "open_inventory": return INVENTORY;
            case "mine": case "dig": case "break": case "harvest": return MINE;
            case "build": case "construct": case "place": case "build_structure": return BUILD;
            case "farm": case "farming": case "harvest_crops": return FARM;
            case "plant": case "replant": case "plant_sapling": case "plant_saplings": case "plant_trees": case "reforest": return PLANT;
            case "patrol": case "scout": case "scouting": case "lookout": return PATROL;
            case "heal": case "healing": case "rest_heal": return HEAL;
            case "equip": case "equipment": case "gear": case "armor": return EQUIP;
            case "trade": case "trading": case "barter": return TRADE;
            case "loot": case "collect": case "gather": return LOOT;
            case "explore": case "explore_area": return EXPLORE;
            case "wander": case "wander_around": case "roam": return WANDER;
            case "defense": return DEFEND;
            case "retreat": case "run_away": case "escape": return RETREAT;
            case "hold_on": case "pause": return WAIT;
            case "sneak": case "sneaking": case "stealth": return SNEAK;
            case "jump": case "jumping": case "leap": return JUMP;
            case "dance": case "celebrate": case "party": return DANCE;
            case "sleep": case "sleeping": case "rest_sleep": return SLEEP;
            case "wake": case "wake_up": case "awake": return WAKE;
            case "give": case "hand": case "hand_over": return GIVE;
            case "take": case "grab": case "hold_item": return TAKE;
            case "drop": case "drop_item": case "throw": return DROP;
            case "pickup": case "pick_up": case "grab_item": return PICKUP;
            case "open": case "open_door": case "open_chest": return OPEN;
            case "close": case "close_door": case "close_chest": return CLOSE;
            case "craft": case "crafting": case "make": return CRAFT;
            case "smelt": case "smelting": return SMELT;
            case "brew": case "brewing": case "potion": return BREW;
            case "enchant": case "enchanting": case "enchant_item": return ENCHANT;
            case "fish": case "fishing": case "catch_fish": return FISH;
            case "cook": case "cooking": case "prepare_food": return COOK;
            case "fight": case "fighting": case "combat": return FIGHT;
            case "flee": case "fleeing": case "run": return FLEE;
            case "hide": case "hiding": case "conceal": return HIDE;
            case "seek": case "seeking": case "look_for": return SEEK;
            case "find": case "finding": case "locate": return FIND;
            case "search": case "searching": case "scan": return SEARCH;
            case "seek_block": case "find_block": case "look_for_block": case "go_find": case "dig_for": return SEEK_BLOCK;
            case "track": case "tracking": case "follow_track": return TRACK;
            case "hunt": case "hunting": case "stalk": return HUNT;
            case "remember": case "memorize": case "remember_that": return REMEMBER;
            case "forget": case "forget_that": return FORGET;
            case "set_name": case "rename": case "name": case "call_you": return SET_NAME;
            case "set_skin": case "skin": case "change_skin": case "appearance": return SET_SKIN;
            case "feminine": case "female": case "feminine_model": return FEMININE;
            case "masculine": case "male": case "masculine_model": return MASCULINE;
            case "revive": case "revive_me": case "rescue": case "help_me": return REVIVE;
            case "plan": case "goal": case "autonomy": return PLAN;
            default: return NONE;
        }
    }
}
