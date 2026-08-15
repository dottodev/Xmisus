package com.shadow.mlbbcheat.utils;

import static org.junit.Assert.*;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HoneypotDetectorTest {

    @Test
    public void identicalHp_acrossEntitiesIsSuspicious() {
        HoneypotDetector d = new HoneypotDetector();
        List<PlayerData> players = new ArrayList<>();
        players.add(new PlayerData(1, true, 10f, 10f, 500f));
        players.add(new PlayerData(2, true, 200f, 200f, 500f));
        assertTrue(d.assess(players).suspicious);
    }

    @Test
    public void stackedEntities_areSuspicious() {
        HoneypotDetector d = new HoneypotDetector();
        List<PlayerData> players = new ArrayList<>();
        players.add(new PlayerData(1, true, 10f, 10f, 500f));
        players.add(new PlayerData(2, true, 10f, 10f, 800f));
        assertTrue(d.assess(players).suspicious);
    }

    @Test
    public void frozenPositions_areSuspicious() {
        HoneypotDetector d = new HoneypotDetector();
        List<PlayerData> players = new ArrayList<>();
        players.add(new PlayerData(1, true, 100f, 100f, 500f));
        players.add(new PlayerData(2, true, 300f, 300f, 800f));
        for (int i = 0; i < 20; i++) {
            d.assess(players);
        }
        assertTrue(d.assess(players).suspicious);
    }

    @Test
    public void movingPlayers_areClean() {
        HoneypotDetector d = new HoneypotDetector();
        List<PlayerData> players = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            players.clear();
            players.add(new PlayerData(1, true, 100f + i * 3f, 100f, 500f));
            players.add(new PlayerData(2, true, 300f - i * 2f, 300f, 800f));
            assertFalse(d.assess(players).suspicious);
        }
    }
}
