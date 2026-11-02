package de.cryptocraft.qa;

import de.cryptocraft.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Opt-in destructive fixture for disposable loopback servers ONLY. Shuts down after each phase. */
public final class ServerSmokePlugin extends JavaPlugin {
    private CryptoCraftPlugin crypto;
    private Player player;
    private OfflinePlayer account;
    private CryptoBoard terminal;
    private Object economy;
    private Class<?> economyApi;
    private long deadline;
    private boolean finished;
    private final List<String> checks = new ArrayList<>();

    @Override public void onEnable() {
        if (!Boolean.getBoolean("cryptocraft.qa.disposable")) {
            getLogger().severe("Refusing to run without -Dcryptocraft.qa.disposable=true");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        deadline = System.currentTimeMillis() + 120000;
        if (Boolean.getBoolean("cryptocraft.qa.visual")) { later(40, this::visual); return; }
        later(40, this::start);
    }
    private void visual() throws Exception {
        crypto = (CryptoCraftPlugin) Bukkit.getPluginManager().getPlugin("CryptoCraft");
        if (crypto == null || !crypto.isEnabled()) throw new IllegalStateException("CryptoCraft did not start");
        Bukkit.getOfflinePlayer("CryptoVisualQA").setWhitelisted(true);
        for (CryptoBoard existing : crypto.boards().getBoards()) crypto.boards().remove(existing);
        World world = Bukkit.getWorlds().getFirst();
        world.setTime(6000); world.setStorm(false);
        for (int x=-8;x<=18;x++) for (int z=-5;z<=10;z++) world.getBlockAt(x,80,z).setType(Material.SMOOTH_STONE);
        List<QuotePair> pairs=List.of(new QuotePair("BTC","bitcoin","EUR"),new QuotePair("ETH","ethereum","EUR"),
                new QuotePair("SOL","solana","EUR"),new QuotePair("XRP","ripple","EUR"),
                new QuotePair("ADA","cardano","EUR"),new QuotePair("DOGE","dogecoin","EUR"));
        UUID owner=UUID.nameUUIDFromBytes("OfflinePlayer:CryptoVisualQA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int size=1;size<=3;size++) {
            int x=size==1?0:size==2?5:11;
            CryptoBoard board=crypto.boards().createMarket(owner,"CryptoVisualQA",world.getBlockAt(x,80,0),pairs,BlockFace.SOUTH);
            crypto.boards().replace(board.edited(board.symbol(),board.coinId(),board.currency(),board.displayFacing(),
                    board.settings().with("size",String.valueOf(size)).with("height","2.35")).withTerminal(true));
        }
        CryptoBoard chart=crypto.boards().create(owner,"CryptoVisualQA",world.getBlockAt(5,80,-4),"BTC","bitcoin","EUR",BlockFace.SOUTH);
        crypto.boards().replace(chart.edited(chart.symbol(),chart.coinId(),chart.currency(),chart.displayFacing(),
                chart.settings().with("size","2").with("height","2.35").with("theme","ocean").with("range","true")).withTerminal(true));
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void joined(org.bukkit.event.player.PlayerJoinEvent event) {
                Player viewer=event.getPlayer();
                if(!viewer.getName().equals("CryptoVisualQA"))return;
                viewer.setOp(true); viewer.setGameMode(GameMode.CREATIVE);
                viewer.teleport(new Location(world,5.5,81,5.5,180,0));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"eco set CryptoVisualQA 10000");
                crypto.trading().grant(viewer.getUniqueId(),pairs.getFirst(),new BigDecimal("2"),"Disposable visual fixture");
            }
        },this);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            if (Files.exists(getDataFolder().toPath().resolve("stop-visual"))) { Bukkit.shutdown(); return; }
            try { visualQuotes(pairs); } catch(Exception error) { getLogger().log(java.util.logging.Level.SEVERE,"Visual fixture quote failure",error); }
        },1,40);
        getLogger().info("CRYPTOCRAFT_VISUAL_READY localhost fixture");
    }
    @SuppressWarnings("unchecked")
    private void visualQuotes(List<QuotePair> pairs) throws Exception {
        Field quotesField=CryptoPriceService.class.getDeclaredField("quotes"); quotesField.setAccessible(true);
        Field historyField=CryptoPriceService.class.getDeclaredField("history"); historyField.setAccessible(true);
        var quotes=(Map<String,CryptoPriceService.Quote>)quotesField.get(crypto.prices());
        var history=(Map<String,List<CryptoPriceService.PricePoint>>)historyField.get(crypto.prices());
        Map<String,CryptoPriceService.Quote> snapshots=new HashMap<>();
        String[] values={"64500.25","2450.75","145.50","0.54","0.34","0.112"};
        for(int i=0;i<pairs.size();i++) {
            var pair=pairs.get(i); var price=new BigDecimal(values[i]);
            var quote=new CryptoPriceService.Quote(price,new BigDecimal(i%2==0?"2.34":"-1.25"),Instant.now(),true);
            quotes.put(pair.key(),quote); snapshots.put(pair.key(),quote);
            List<CryptoPriceService.PricePoint> points=new ArrayList<>();
            for(int j=0;j<100;j++) points.add(new CryptoPriceService.PricePoint(Instant.now().minusSeconds((100-j)*300L),
                    price.multiply(BigDecimal.valueOf(1+Math.sin(j*.15)*.02))));
            history.put(pair.key(),List.copyOf(points));
        }
        crypto.trading().onQuotes(snapshots);
    }
    private void start() throws Exception {
        crypto = (CryptoCraftPlugin) Bukkit.getPluginManager().getPlugin("CryptoCraft");
        check(crypto != null && crypto.isEnabled(), "plugin enabled");
        check(crypto.getConfig().getConfigurationSection("coins").getKeys(false).size() == 3,
                "legacy coin catalog preserved");
        check(crypto.trading().available(), "Vault economy available: " + crypto.trading().health());
        economyApi = Class.forName("net.milkbowl.vault.economy.Economy", true,
                Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object provider = Bukkit.getServicesManager().getRegistration((Class) economyApi).getProvider();
        economy = provider;
        account = Bukkit.getOfflinePlayer("CryptoCraftQA");
        player = fakePlayer(account, Bukkit.getWorlds().getFirst());
        Files.createDirectories(getDataFolder().toPath());
        Path checkpoint = getDataFolder().toPath().resolve("checkpoint.txt");
        if (Files.exists(checkpoint)) { restored(Files.readString(checkpoint).trim()); return; }
        callEconomy("createPlayerAccount", new Class<?>[]{OfflinePlayer.class}, account);
        double balance = balance();
        if (balance > 0) response(callEconomy("withdrawPlayer", new Class<?>[]{OfflinePlayer.class,double.class}, account,balance));
        response(callEconomy("depositPlayer", new Class<?>[]{OfflinePlayer.class,double.class}, account,1000d));
        createTerminal();
        check(crypto.boards().nearestTerminal(player, 6) != null, "terminal access within six blocks");
        publish(false);
        boolean rejected = false;
        try { crypto.trading().preview(player,"BTC",true,new BigDecimal("100"),false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected,"unstamped provider quote rejected");
        publish(true);
        var preview = crypto.trading().preview(player,"BTC",true,new BigDecimal("100"),false);
        var submitted = crypto.trading().submit(player,preview);
        rejected = false;
        try { crypto.trading().submit(player,preview); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected,"confirmation accepted only once");
        then(submitted, order -> waitOpen(order.id(), () -> {
            check(Math.abs(balance()-900d)<0.00001,"real Vault budget withdrawn once");
            check(!account.isOnline(),"account executes while offline");
            publish(true);
            waitStatus(order.id(), TradingEngine.OrderStatus.FILLED, () -> then(crypto.trading().view(account.getUniqueId()), view -> {
                check(view.coins().get("bitcoin").available().compareTo(BigDecimal.ONE)==0,"external-price buy credited one BTC");
                sell();
            }));
        }));
    }
    private void sell() throws Exception {
        publish(true);
        var preview = crypto.trading().preview(player,"BTC",false,BigDecimal.ONE,true);
        then(crypto.trading().submit(player,preview), order -> later(2, () -> {
            publish(true);
            waitStatus(order.id(),TradingEngine.OrderStatus.FILLED, () -> waitPaid(() -> {
                check(Math.abs(balance()-1000d)<0.00001,"real Vault sale payout conserved money");
                cancellation();
            }));
        }));
    }
    private void cancellation() throws Exception {
        publish(true);
        var preview = crypto.trading().preview(player,"BTC",true,new BigDecimal("50"),false);
        then(crypto.trading().submit(player,preview), order -> waitOpen(order.id(), () ->
            then(crypto.trading().cancel(player,order.id()), ignored -> waitPaid(() -> {
                check(Math.abs(balance()-1000d)<0.00001,"cancellation refunded reserved budget");
                terminalRevocation();
            }))));
    }
    private void terminalRevocation() throws Exception {
        publish(true);
        var preview = crypto.trading().preview(player,"BTC",true,new BigDecimal("10"),false);
        crypto.boards().replace(terminal.withTerminal(false));
        boolean rejected = false;
        try { crypto.trading().submit(player,preview); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected,"terminal revocation checked at confirmation");
        crypto.boards().replace(terminal);
        checkEntities();
        Files.writeString(getDataFolder().toPath().resolve("checkpoint.txt"),terminal.id());
        finish("phase1");
    }
    private void restored(String id) throws Exception {
        terminal = crypto.boards().getBoard(id);
        check(terminal!=null && terminal.market() && terminal.terminal(),"market terminal persisted across restart");
        // A headless offline account does not load chunks like a connected client would.
        // Production boards intentionally never force-load their own chunks.
        World world = player.getWorld();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            world.getChunkAt((terminal.x() >> 4) + x, (terminal.z() >> 4) + z).load();
        crypto.boards().ensureLoadedChunks(crypto.prices());
        checkEntities();
        then(crypto.trading().history(account.getUniqueId()), history -> {
            check(history.size()==2,"SQLite trade journal survived restart");
            then(crypto.trading().view(account.getUniqueId()), view -> {
                check(view.coins().isEmpty() && view.cashAvailable().signum()==0 && !view.frozen(),"settled depot survived restart");
                crypto.boards().remove(terminal);
                check(tagged().isEmpty(),"terminal removal cleaned map and block entities");
                finish("phase2");
            });
        });
    }
    private void createTerminal() {
        World world = player.getWorld();
        // The snapped map plane puts the back of this origin fixture into chunk z=-1.
        // A real nearby player loads it; this offline fixture must do that explicitly.
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            world.getChunkAt(x,z).load();
        var block = world.getBlockAt(0,80,0);
        block.setType(Material.STONE);
        terminal = crypto.boards().createMarket(account.getUniqueId(),"CryptoCraftQA",block,List.of(
                new QuotePair("BTC","bitcoin","EUR"),new QuotePair("ETH","ethereum","EUR"),
                new QuotePair("SOL","solana","EUR")),BlockFace.SOUTH);
        terminal = terminal.edited(terminal.symbol(),terminal.coinId(),terminal.currency(),terminal.displayFacing(),
                terminal.settings().with("height","2.35")).withTerminal(true);
        crypto.boards().replace(terminal);
        checkEntities();
    }
    private List<Entity> tagged() {
        var key = new NamespacedKey(crypto,"board_id");
        return player.getWorld().getEntities().stream().filter(e -> terminal.id().equals(
                e.getPersistentDataContainer().get(key,PersistentDataType.STRING))).toList();
    }
    private void checkEntities() {
        var entities = tagged();
        long tiles = entities.stream().filter(ItemFrame.class::isInstance).count();
        check(tiles==4,"2x2 map screen has four tiles (observed " + tiles + ")");
        long components = entities.stream().filter(BlockDisplay.class::isInstance).count();
        check(components==30,"vanilla computer case stand and keyboard spawned (observed " + components + ")");
        int count = entities.size();
        crypto.boards().ensureLoadedChunks(crypto.prices());
        check(tagged().size()==count,"display reconciliation does not duplicate entities");
    }
    @SuppressWarnings("unchecked")
    private void publish(boolean verified) throws Exception {
        var quote = new CryptoPriceService.Quote(new BigDecimal("100"),BigDecimal.ZERO,Instant.now(),verified);
        Field field = CryptoPriceService.class.getDeclaredField("quotes");
        field.setAccessible(true);
        ((Map<String,CryptoPriceService.Quote>)field.get(crypto.prices())).put("bitcoin:eur",quote);
        crypto.trading().onQuotes(Map.of("bitcoin:eur",quote));
    }
    private void waitOpen(String id, Step next) { waitStatus(id,TradingEngine.OrderStatus.OPEN,next); }
    private void waitStatus(String id,TradingEngine.OrderStatus status,Step next) {
        then(crypto.trading().orders(account.getUniqueId()), orders -> {
            var order = orders.stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();
            if(order.status()==status) next.run();
            else if(!order.active()) throw new AssertionError("Unexpected order status "+order);
            else later(5,()->waitStatus(id,status,next));
        });
    }
    private void waitPaid(Step next) {
        then(crypto.trading().view(account.getUniqueId()), view -> {
            if(view.cashAvailable().signum()==0 && view.cashReserved().signum()==0 && !view.frozen()) next.run();
            else later(5,()->waitPaid(next));
        });
    }
    private Player fakePlayer(OfflinePlayer offline,World world) {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()) {
            case "getUniqueId" -> offline.getUniqueId();
            case "getName" -> "CryptoCraftQA";
            case "getWorld" -> world;
            case "getLocation" -> new Location(world,.5,82.35,2.5);
            case "hasPermission","isOnline","isOp" -> true;
            case "toString" -> "CryptoCraft disposable QA player";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            default -> { if(method.getReturnType()==boolean.class) yield false; if(method.getReturnType()==int.class) yield 0; yield null; }
        });
    }
    private Object callEconomy(String method,Class<?>[] types,Object... args) throws Exception {
        return economyApi.getMethod(method,types).invoke(economy,args);
    }
    private double balance() throws Exception { return ((Number)callEconomy("getBalance",new Class<?>[]{OfflinePlayer.class},account)).doubleValue(); }
    private void response(Object response) throws Exception {
        check(Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response)),"Vault fixture transaction successful");
    }
    private void check(boolean condition,String description) {
        if(!condition) throw new AssertionError(description);
        checks.add(description); getLogger().info("QA PASS: "+description);
    }
    private <T> void then(CompletableFuture<T> future,CheckedConsumer<T> next) {
        future.whenComplete((value,error)->later(1,()->{if(error!=null) throw new RuntimeException(error); next.accept(value);}));
    }
    private void later(long ticks,Step step) {
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(finished)return;
            try { if(System.currentTimeMillis()>deadline) throw new AssertionError("QA timeout"); step.run(); }
            catch(Throwable error) { failed(error); }
        },ticks);
    }
    private void finish(String phase) throws Exception {
        Files.writeString(getDataFolder().toPath().resolve(phase+"-passed.txt"),Bukkit.getVersion()+"\n"+String.join("\n",checks));
        getLogger().info("CRYPTOCRAFT_QA_SUCCESS "+phase); finished=true; Bukkit.shutdown();
    }
    private void failed(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE,"CRYPTOCRAFT_QA_FAILED",error);
        try { Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("failed.txt"),error.toString()); } catch(Exception ignored){}
        finished=true; Bukkit.shutdown();
    }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    @FunctionalInterface private interface CheckedConsumer<T> { void accept(T value) throws Exception; }
}
