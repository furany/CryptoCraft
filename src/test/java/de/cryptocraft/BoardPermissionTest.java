package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

class BoardPermissionTest {
    private CryptoCraftPlugin plugin;
    private CryptoBoardService boards;
    private Player player;
    private CryptoCommand command;
    private CryptoBoard board;

    @BeforeEach
    void setup() {
        plugin = mock(CryptoCraftPlugin.class);
        boards = mock(CryptoBoardService.class);
        player = mock(Player.class);
        var messages = mock(CryptoMessages.class);
        when(messages.get(anyString())).thenAnswer(call -> call.getArgument(0));
        when(plugin.messages()).thenReturn(messages);
        when(plugin.boards()).thenReturn(boards);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        UUID owner = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);
        when(player.hasPermission("cryptocraft.use")).thenReturn(true);
        when(player.hasPermission("cryptocraft.edit")).thenReturn(true);
        board =
                new CryptoBoard(
                        "board",
                        owner,
                        "owner",
                        UUID.randomUUID(),
                        "world",
                        0,
                        64,
                        0,
                        "BTC",
                        "bitcoin",
                        "EUR",
                        BlockFace.SOUTH,
                        new BoardSettings("", 1.35, 24, "dark", "chart", 1, false));
        when(boards.getBoard("board")).thenReturn(board);
        command = new CryptoCommand(plugin);
    }

    @Test
    void cannotEditOrRemoveAnotherPlayersBoard() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        assertThrows(
                IllegalArgumentException.class,
                () -> command.editBoard(player, "board", "hours", "1"));
        assertThrows(IllegalArgumentException.class, () -> command.removeBoard(player, "board"));
        verify(boards, never()).replace(any());
        verify(boards, never()).remove(any());
    }

    @Test
    void editsRecheckPermissionRatherThanTrustingAnOpenMenu() {
        when(player.hasPermission("cryptocraft.edit")).thenReturn(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> command.editBoard(player, "board", "hours", "1"));
        verify(boards, never()).replace(any());
    }

    @Test
    void regionDenialPreventsOwnedBoardChanges() {
        World world = mock(World.class);
        var protection = mock(ProtectionService.class);
        when(plugin.protection()).thenReturn(protection);
        when(boards.resolveWorld(board)).thenReturn(world);
        when(protection.canBuild(eq(player), any())).thenReturn(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> command.editBoard(player, "board", "hours", "1"));
        verify(boards, never()).replace(any());
    }

    @Test
    void dottedPermissionLimitsUseHighestMatchingLeaf() {
        when(plugin.getConfig())
                .thenReturn(
                        YamlConfiguration.loadConfiguration(
                                new java.io.File("src/main/resources/config.yml")));
        assertEquals(1, command.getBoardLimit(player));
        when(player.hasPermission("cryptocraft.limit.vip")).thenReturn(true);
        assertEquals(3, command.getBoardLimit(player));
        when(player.hasPermission("cryptocraft.limit.elite")).thenReturn(true);
        assertEquals(5, command.getBoardLimit(player));
    }
}
