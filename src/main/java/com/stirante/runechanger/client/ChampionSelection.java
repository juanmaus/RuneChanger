package com.stirante.runechanger.client;

import com.stirante.eventbus.BusEvent;
import com.stirante.eventbus.EventBus;
import com.stirante.eventbus.Subscribe;
import com.stirante.lolclient.ApiResponse;
import com.stirante.lolclient.ClientApi;
import com.stirante.runechanger.DebugConsts;
import com.stirante.runechanger.RuneChanger;
import com.stirante.runechanger.model.client.Champion;
import com.stirante.runechanger.model.client.GameMap;
import com.stirante.runechanger.model.client.GameMode;
import com.stirante.runechanger.model.client.SummonerSpell;
import com.stirante.runechanger.sourcestore.TeamCompAnalyzer;
import com.stirante.runechanger.util.AnalyticsUtil;
import com.stirante.runechanger.util.SimplePreferences;
import generated.*;
import ly.count.sdk.java.Countly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class ChampionSelection extends ClientModule {
    private static final Logger log = LoggerFactory.getLogger(ChampionSelection.class);

    private Map<String, Object> action;
    private Champion champion;
    private GameMode gameMode;
    private boolean positionSelector;
    private final ArrayList<Champion> banned = new ArrayList<>();
    private Map<String, Object> banAction;
    private final Map<Position, Champion> allyTeam = new HashMap<>();
    private final List<Champion> enemyTeam = new ArrayList<>();
    private Position selectedPosition;
    private GameMap map;
    private String currentPhase = "";
    private long phaseEnd = 0L;
    private int skinId = 0;
    private long wardSkinId = 0;
    private String chatRoomName;
    private final TeamCompAnalyzer teamCompAnalyzer = new TeamCompAnalyzer();

    public ChampionSelection(ClientApi api) {
        super(api);
        EventBus.register(this);
    }

    public LolChampSelectChampSelectSession getSession() throws IOException {
        ApiResponse<LolChampSelectChampSelectSession> session =
                getApi().executeGet("/lol-champ-select/v1/session", LolChampSelectChampSelectSession.class);
        if (session.isOk()) {
            return session.getResponseObject();
        }
        return null;
    }

    public List<Champion> getLastChampions() {
        try {
            ApiResponse<LolMatchHistoryMatchHistoryList> recentlyPlayed =
                    getApi().executeGet(
                            "/lol-match-history/v1/products/lol/current-summoner/matches",
                            LolMatchHistoryMatchHistoryList.class, "begIndex", "0", "endIndex", "20");
            if (!recentlyPlayed.isOk() || recentlyPlayed.getResponseObject().games == null ||
                    recentlyPlayed.getResponseObject().games.games.size() == 0) {
                return null;
            }
            List<Champion> result = new ArrayList<>();
            List<LolMatchHistoryMatchHistoryGame> games = recentlyPlayed.getResponseObject().games.games;
            for (LolMatchHistoryMatchHistoryGame game : games) {
                int participantId = game.participantIdentities.stream()
                        .filter(id -> Objects.equals(id.player.summonerId, getCurrentSummoner().summonerId))
                        .mapToInt(value -> value.participantId)
                        .findFirst()
                        .orElse(-1);
                if (participantId == -1) {
                    continue;
                }
                Champion champion = game.participants.stream()
                        .filter(participant -> participant.participantId == participantId)
                        .map(participant -> Champion.getById(participant.championId))
                        .findFirst()
                        .orElse(null);
                if (champion != null && !result.contains(champion)) {
                    result.add(champion);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Exception occurred while getting last picked champions", e);
            return null;
        }
    }

    public Champion getSelectedChampion() {
        return champion;
    }

    public ArrayList<Champion> getBannedChampions() {
        return banned;
    }

    public void banChampion(Champion champion) {
        if (banAction != null) {
            //{"actorCellId":0.0,"championId":0.0,"completed":false,"id":0.0,"isAllyAction":false,"isInProgress":true,"type":"ban"}
            banAction.put("championId", champion.getId());
            int id = ((Double) banAction.get("id")).intValue();
            try {
                getApi().executePatch("/lol-champ-select/v1/session/actions/" + id, banAction);
                getApi().executePost("/lol-champ-select/v1/session/actions/" + id + "/complete", banAction);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public Map<Position, Champion> getAllyTeam() {
        return allyTeam;
    }

    public List<Champion> getEnemyTeam() {
        return enemyTeam;
    }

    @SuppressWarnings("unchecked")
    private void processActions(LolChampSelectChampSelectSession session) {
        banned.clear();
        enemyTeam.clear();
        allyTeam.clear();
        banAction = null;
        //we need to find summoner's cell id
        LolChampSelectChampSelectPlayerSelection self =
                session.myTeam.stream()
                        .filter(player -> player.summonerId.equals(getCurrentSummoner().summonerId))
                        .findFirst()
                        .orElse(null);
        //should never be null, but i'll check just in case
        if (!DebugConsts.MOCK_SESSION && self != null) {
            for (Object actions : session.actions) {
                for (Object action : ((List) actions)) {
                    Map<String, Object> a = (Map<String, Object>) action;
                    //no idea why, but cell id gets recognized here as Double
                    int actorCellId = ((Double) a.get("actorCellId")).intValue();
                    String type = String.valueOf(a.get("type"));
                    if (type.equals("pick")) {
                        if (actorCellId == self.cellId.intValue()) {
                            this.action = a;
                        }
                        else if (session.theirTeam.stream()
                                .anyMatch(player -> player.cellId.intValue() == actorCellId)) {
                            this.enemyTeam.add(Champion.getById(((Double) a.get("championId")).intValue()));
                        }
                        else if (session.myTeam.stream().anyMatch(player -> player.cellId.intValue() == actorCellId)) {
                            Optional<LolChampSelectChampSelectPlayerSelection> first = session.myTeam.stream()
                                    .filter(player -> player.cellId.intValue() == actorCellId)
                                    .findFirst();
                            if (first.isPresent() && first.get().assignedPosition != null &&
                                    !first.get().assignedPosition.isBlank()) {
                                this.allyTeam.put(Position.valueOf(first.get().assignedPosition.toUpperCase(Locale.ROOT)), Champion.getById(((Double) a.get("championId")).intValue()));
                            }
                        }
                    }
                    if (type.equals("ban") && ((Boolean) a.get("completed"))) {
                        int championId = ((Double) a.get("championId")).intValue();
                        if (championId != 0) {
                            banned.add(Champion.getById(championId));
                        }
                    }
                    else if (type.equals("ban") &&
                            actorCellId == self.cellId.intValue() &&
                            !((Boolean) a.get("completed"))) {
                        banAction = a;
                    }
                }
            }
        }
        else if (DebugConsts.MOCK_SESSION) {
            //mock some banned champions
            banned.add(Champion.getByName("blitzcrank"));
            banned.add(Champion.getByName("morgana"));
            banned.add(Champion.getByName("kayle"));
            banned.add(Champion.getByName("leona"));
        }
    }

    private void findSelectedChampion(LolChampSelectChampSelectSession session) {
        //find selected champion
        for (LolChampSelectChampSelectPlayerSelection selection : session.myTeam) {
            if (selection != null && Objects.equals(selection.summonerId, getCurrentSummoner().summonerId)) {
                //first check locked champion
                champion = Champion.getById(selection.championId);
                skinId = selection.selectedSkinId == null ? 0 : selection.selectedSkinId;
                wardSkinId = selection.wardSkinId == null ? 0 : selection.wardSkinId;
                //if it fails check just selected champion
                if (champion == null) {
                    champion = Champion.getById(selection.championPickIntent);
                }
                //if all fails check list of actions
                if (champion == null && action != null) {
                    champion = Champion.getById(((Double) action.get("championId")).intValue());
                }
                selectedPosition = null;
                try {
                    if (selection.assignedPosition != null) {
                        selectedPosition = Position.valueOf(selection.assignedPosition.toUpperCase(Locale.ROOT));
                    }
                } catch (IllegalArgumentException e) {
                    //ignore
                }
                break;
            }
        }
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public GameMap getMap() {
        return map;
    }

    public boolean isChampionLocked() {
        if (action != null && action.containsKey("completed")) {
            return ((boolean) action.get("completed"));
        }
        return false;
    }

    private void updateGameMode() {
        if (!DebugConsts.MOCK_SESSION) {
            try {
                ApiResponse<LolLobbyLobbyDto> lolLobbyLobbyDto =
                        getApi().executeGet("/lol-lobby/v2/lobby", LolLobbyLobbyDto.class);
                if (lolLobbyLobbyDto.isOk()) {
                    map = GameMap.getById(lolLobbyLobbyDto.getResponseObject().gameConfig.mapId);
                    positionSelector = lolLobbyLobbyDto.getResponseObject().gameConfig.showPositionSelector;
                    gameMode = GameMode.valueOf(lolLobbyLobbyDto.getResponseObject().gameConfig.gameMode);
                }
                else {
                    positionSelector = false;
                    gameMode = GameMode.CLASSIC;
                    map = GameMap.MAP_11;
                }
            } catch (IOException e) {
                positionSelector = false;
                gameMode = GameMode.CLASSIC;
                map = GameMap.MAP_11;
            } catch (IllegalArgumentException e) {
                log.error("Exception thrown when updating the gamemode! GameMode.java might not be updated. " +
                        e.getMessage());
                if (Countly.isInitialized()) {
                    AnalyticsUtil.addCrashReport(e, "Exception thrown when updating the gamemode! GameMode.java might not be updated.", false);
                }
                positionSelector = false;
                gameMode = GameMode.CLASSIC;
                map = GameMap.MAP_11;
            }
        }
        else {
            //mock classic game without positions selector
            gameMode = GameMode.CLASSIC;
            map = GameMap.MAP_11;
            positionSelector = false;
        }
    }

    @Subscribe(ClientEventListener.ChampionSelectionEvent.NAME)
    public void onSession(ClientEventListener.ChampionSelectionEvent event) {
        if (event.getEventType() == ClientEventListener.WebSocketEventType.DELETE) {
            clearSession();
            EventBus.publish(ChampionSelectionEndEvent.NAME, new ChampionSelectionEndEvent());
            EventBus.publish(TeamCompAnalyzer.TeamCompAnalysisEvent.NAME, new TeamCompAnalyzer.TeamCompAnalysisEvent(null));
        }
        else {
            Champion oldChampion = champion;
            chatRoomName = event.getData().chatDetails.chatRoomName;
            updateGameMode();
            if (gameMode.isDisabled()) {
                return;
            }
            Map<Position, Champion> oldChampions = new HashMap<>(allyTeam);
            List<Champion> oldEnemyChampions = new ArrayList<>(enemyTeam);
            processActions(event.getData());
            findSelectedChampion(event.getData());
            if (isPositionSelector()) {
                for (LolChampSelectChampSelectPlayerSelection selection : event.getData().myTeam) {
                    if (selection.assignedPosition != null && Champion.getById(selection.championId) != null) {
                        allyTeam.put(Position.valueOf(selection.assignedPosition.toUpperCase(Locale.ROOT)), Champion.getById(selection.championId));
                    }
                }
            }
            boolean teamChanged =
                    oldChampions.size() != allyTeam.size() || oldEnemyChampions.size() != enemyTeam.size();
            if (!teamChanged) {
                for (int i = 0, oldEnemyChampionsSize = oldEnemyChampions.size(); i < oldEnemyChampionsSize; i++) {
                    Champion oldEnemyChampion = oldEnemyChampions.get(i);
                    if (oldEnemyChampion != enemyTeam.get(i)) {
                        teamChanged = true;
                        break;
                    }
                }
                if (!teamChanged) {
                    for (Position position : oldChampions.keySet()) {
                        if (oldChampions.get(position) != allyTeam.get(position)) {
                            teamChanged = true;
                            break;
                        }
                    }
                }
            }
            if (DebugConsts.FORCE_TEAM_COMP_ANALYSIS) {
                selectedPosition = Position.UTILITY;
            }
            if ((isPositionSelector() || DebugConsts.FORCE_TEAM_COMP_ANALYSIS) && selectedPosition != null &&
                    (!allyTeam.isEmpty() || !enemyTeam.isEmpty()) &&
                    teamChanged) {
                System.out.println("Team changed, analyzing new team");
                RuneChanger.EXECUTOR_SERVICE.submit(() -> {
                    try {
                        teamCompAnalyzer.analyze(selectedPosition, champion, allyTeam, enemyTeam, banned);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
            LolChampSelectChampSelectTimer timer = event.getData().timer;
            if (timer != null) {
                currentPhase = timer.phase;
                phaseEnd = timer.internalNowInEpochMs + timer.adjustedTimeLeftInPhase;
            }
            if (event.getEventType() == ClientEventListener.WebSocketEventType.CREATE || oldChampion != champion) {
                EventBus.publish(ChampionChangedEvent.NAME, new ChampionChangedEvent(champion));
            }
            if (event.getEventType() == ClientEventListener.WebSocketEventType.CREATE &&
                    SimplePreferences.getBooleanValue(SimplePreferences.SettingsKeys.AUTO_MESSAGE, false)) {
                String message = SimplePreferences.getStringValue(SimplePreferences.SettingsKeys.AUTO_MESSAGE_TEXT, "");
                if (message != null && !message.isEmpty()) {
                    Timer t = new Timer();
                    t.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            try {
                                LolChampSelectChampSelectSession session = getSession();
                                if (session != null) {
                                    sendMessageToChampSelect(message);
                                }
                            } catch (IOException e) {
                                log.error("Exception occurred while sending automatic message", e);
                            }
                        }
                    }, 3000);
                }
            }
        }
    }

    @Subscribe(ClientEventListener.CurrentSummonerEvent.NAME)
    public void onCurrentSummoner(ClientEventListener.CurrentSummonerEvent event) {
        resetSummoner();
    }

    public void selectChampion(Champion champion) {
        try {
            if (action == null) {
                log.debug("No action to select champion");
                getApi().executePost("/lol-champ-select/v1/current-champion", champion.getId());
                return;
            }
            if (getGameMode() == GameMode.ONEFORALL) {
                getApi().executePost("/lol-champ-select/v1/current-champion", champion.getId());
            }
            else {
                action.put("championId", champion.getId());
                action.put("completed", false);
                getApi().executePatch("/lol-champ-select/v1/session/actions/" +
                        ((Double) action.get("id")).intValue(), action);
            }
        } catch (IOException e) {
            log.error("Exception occurred while soft picking champion", e);
        }
    }

    public void sendMessageToChampSelect(String msg) {
        if (chatRoomName == null) {
            return;
        }
        String name = chatRoomName.substring(0, chatRoomName.indexOf('@'));
        LolChatConversationMessageResource message = new LolChatConversationMessageResource();
        message.body = msg;
        message.type = "chat";
        try {
            getApi().executePost("/lol-chat/v1/conversations/" + name + "/messages", message);
        } catch (IOException e) {
            log.error("Exception occurred while sending a message", e);
        }
    }

    public void setSummonerSpells(SummonerSpell spell1, SummonerSpell spell2) {
        try {
            LolChampSelectChampSelectMySelection selection = new LolChampSelectChampSelectMySelection();
            boolean flashFirst = SimplePreferences.getBooleanValue(SimplePreferences.SettingsKeys.FLASH_FIRST, true);
            if ((spell1 == SummonerSpell.FLASH && flashFirst) || (spell2 == SummonerSpell.FLASH && !flashFirst) ||
                    (spell1 != SummonerSpell.FLASH && spell2 != SummonerSpell.FLASH)) {
                selection.spell1Id = (long) spell1.getKey();
                selection.spell2Id = (long) spell2.getKey();
            }
            else {
                selection.spell1Id = (long) spell2.getKey();
                selection.spell2Id = (long) spell1.getKey();
            }
            selection.selectedSkinId = skinId;
            selection.wardSkinId = wardSkinId;
            getApi().executePatch("/lol-champ-select/v1/session/my-selection", selection);
        } catch (IOException e) {
            log.error("Exception occurred while setting summoner spells", e);
        }
    }

    public boolean isPositionSelector() {
        return positionSelector;
    }

    public Position getSelectedPosition() {
        return selectedPosition;
    }

    public void clearSession() {
        champion = null;
        action = null;
        positionSelector = false;
        gameMode = null;
        chatRoomName = null;
    }

    public void reset() {
        super.reset();
        clearSession();
    }

    public boolean wasLastGameGood() {
        try {
            ApiResponse<LolMatchHistoryMatchHistoryList> lastMatch =
                    getApi().executeGet("/lol-match-history/v1/products/lol/current-summoner/matches", LolMatchHistoryMatchHistoryList.class, "begIndex", "0", "endIndex", "1");
            if (!lastMatch.isOk() || lastMatch.getResponseObject().games.games.size() > 0) {
                return false;
            }

            LolMatchHistoryMatchHistoryGame game =
                    lastMatch.getResponseObject().games.games.get(0);
            int participantId = game.participantIdentities.stream().filter(pi -> Objects.equals(pi.player.summonerId, getCurrentSummoner().summonerId)).findFirst().orElseThrow().participantId;
            LolMatchHistoryMatchHistoryParticipant stats =
                    game.participants.stream().filter(p -> p.participantId == participantId).findFirst().orElseThrow();

            return stats.stats.win && ((stats.stats.kills + stats.stats.assists) / (double) Math.min(1, stats.stats.deaths)) > 2;
        } catch (Exception e) {
            log.error("Exception occurred while getting last game", e);
            return false;
        }
    }

    public static class ChampionChangedEvent implements BusEvent {
        public static final String NAME = "ChampionChangedEvent";

        private final Champion champion;

        public ChampionChangedEvent(Champion champion) {
            this.champion = champion;
        }

        public Champion getChampion() {
            return champion;
        }
    }

    public static class ChampionSelectionEndEvent implements BusEvent {
        public static final String NAME = "ChampionSelectionEndEvent";

        public ChampionSelectionEndEvent() {
        }

    }

}
