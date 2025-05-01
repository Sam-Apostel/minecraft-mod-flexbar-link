package apostel.flexbarlink;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.inventory.Inventory;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.WebSocket;
import java.net.InetSocketAddress;

import net.minecraft.client.network.ClientPlayerEntity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.mojang.logging.LogUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;


public class FlexbarlinkClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();

	private static ModWebSocketServer webSocketServer;
	private static ExecutorService webSocketExecutor = Executors.newSingleThreadExecutor();

	private static Map<WebSocket, String> clientConnections = new ConcurrentHashMap<>(); // Use ConcurrentHashMap for thread safety
	private static ItemStack[] previousInventory = new ItemStack[41]; // Default 41-slot inventory

	public static int tickCounter = 0; // Counter for ticks
	public static int lastSlot = -1; // Last slot clicked
	public static int lastPlayerSlot = -1; // Last slot clicked

	@Override
	public void onInitializeClient() {
		// Initialize previousInventory with empty ItemStacks
        Arrays.fill(previousInventory, ItemStack.EMPTY);

		// Start the WebSocket server
		int port = 28887; // Choose a port
		webSocketServer = new ModWebSocketServer(new InetSocketAddress(port));
		webSocketServer.start();

		LOGGER.info("Flexbarlink client initialized. WebSocket server started on port: {}", port);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tickCounter++;
			if (tickCounter >= 2) {
				tickCounter = 0;

				ClientPlayerEntity player = client.player;
				if (player != null && player.getInventory() != null) {
					Inventory inventory = player.getInventory();

					List<JsonObject> items = new ArrayList<>();

					int i = 0;
					for (ItemStack currentStack : inventory) {
						if (i >= previousInventory.length) {
							LOGGER.warn("Inventory size exceeded, skipping remaining slots. Inventory size: {}, Previous size: {}", inventory.size(), previousInventory.length);
							break;
						}
						ItemStack previousStack = previousInventory[i];

						if (!ItemStack.areEqual(currentStack, previousStack)) {
							previousInventory[i] = currentStack.copy();

							JsonObject itemJson = new JsonObject();
							itemJson.addProperty("slot", i);

							if (!currentStack.isEmpty()) {
								itemJson.addProperty("name", currentStack.getItem().getName().getString());
								itemJson.addProperty("id", currentStack.getItem().getTranslationKey());
								itemJson.addProperty("count", currentStack.getCount());
								itemJson.addProperty("base64", BlockIdToBase64.get(currentStack.getItemName().toString()));
							} else {
								itemJson.addProperty("name", "");
								itemJson.addProperty("id", "");
								itemJson.addProperty("count", 0);
								itemJson.addProperty("base64", "");
							}

							items.add(itemJson);
						}

						i++;
					}

					if (!items.isEmpty()) {
						JsonObject changesJson = new JsonObject();
						JsonArray jsonArray = new JsonArray();
						for (JsonObject obj : items) jsonArray.add(obj);
						changesJson.add("items", jsonArray);
						String jsonString = changesJson.toString();

						webSocketExecutor.execute(() -> {
							for (WebSocket conn : clientConnections.keySet()) {
								conn.send(jsonString);
							}
						});
					}
				}
			}
		});
	}

	private class ModWebSocketServer extends WebSocketServer {

		public ModWebSocketServer(InetSocketAddress address) {
			super(address);
		}

		@Override
		public void onOpen(WebSocket conn, ClientHandshake handshake) {
            LOGGER.info("WebSocket connection opened: {}", conn.getRemoteSocketAddress());

			// Add connection to clientConnections
			clientConnections.put(conn, conn.getRemoteSocketAddress().toString());

			// Send the entire inventory to the new client
			getPlayerInventoryJsonAsync().thenAccept(conn::send);
		}

		@Override
		public void onMessage(WebSocket conn, String message) {
            LOGGER.info("Received message from {}: {}", conn.getRemoteSocketAddress(), message);

			try {
				JsonObject json = new Gson().fromJson(message, JsonObject.class);
				if (json.has("slot")) {
					int slot = json.get("slot").getAsInt();
					selectSlot(slot);
				} else {
                    LOGGER.warn("Received message does not contain 'slot' field: {}", message);
				}
			} catch (Exception e) {
                LOGGER.error("Error parsing message from client: {}", message, e);
			}
		}

		@Override
		public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            LOGGER.info("WebSocket connection closed: {} Reason: {}", conn.getRemoteSocketAddress(), reason);
			clientConnections.remove(conn);
		}

		@Override
		public void onError(WebSocket conn, Exception ex) {
			LOGGER.info("WebSocket error: ", ex);
		}

		@Override
		public void onStart() {
            LOGGER.info("WebSocket server started on port: {}", getPort());
		}
	}

	// Method to get the player's entire inventory as JSON asynchronously
	private CompletableFuture<String> getPlayerInventoryJsonAsync() {
		CompletableFuture<String> future = new CompletableFuture<>();

		// Schedule the task on the main thread
		MinecraftClient.getInstance().execute(() -> {
			ClientPlayerEntity player = MinecraftClient.getInstance().player;
			if (player != null) {
				Inventory inventory = player.getInventory();
				JsonArray jsonArray = new JsonArray();

				int i = 0;
                for (ItemStack stack : inventory) {
					JsonObject itemJson = new JsonObject();
					itemJson.addProperty("slot", i++);

					if (!stack.isEmpty()) {
						itemJson.addProperty("name", stack.getItem().getName().getString());
						itemJson.addProperty("id", stack.getItem().getTranslationKey());
						itemJson.addProperty("count", stack.getCount());
						itemJson.addProperty("base64", BlockIdToBase64.get(stack.getItemName().toString()));
					} else {
						itemJson.addProperty("name", "");
						itemJson.addProperty("id", "");
						itemJson.addProperty("count", 0);
						itemJson.addProperty("base64", "");
					}
					jsonArray.add(itemJson);
                }

				JsonObject inventoryJson = new JsonObject();
				inventoryJson.add("items", jsonArray);

				future.complete(inventoryJson.toString());
			} else {
				future.complete("{}");
			}
		});

		return future;
	}

	private void selectSlot(int slot) {
		if (slot >= 0 && slot < 9) {
			MinecraftClient.getInstance().execute(() -> {
				ClientPlayerEntity player = MinecraftClient.getInstance().player;
				if (player == null) return;

				player.getInventory().setSelectedSlot(slot);
			});
		}

		// TODO: implement swapping item to hotbar
	}
}