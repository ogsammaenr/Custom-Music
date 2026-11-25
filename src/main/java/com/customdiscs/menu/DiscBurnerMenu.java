package com.customdiscs.menu;

import com.customdiscs.component.CustomDiscData;
import com.customdiscs.registry.ModBlocks;
import com.customdiscs.registry.ModComponents;
import com.customdiscs.registry.ModItems;
import com.customdiscs.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class DiscBurnerMenu extends AbstractContainerMenu {
	public static final int DISC_SLOT_INDEX = 0;
	public static final int DISC_SLOT_X = 16;
	public static final int DISC_SLOT_Y = 29;
	public static final int INV_START_X = 29;
	public static final int INV_START_Y = 156;

	private final Container discContainer = new SimpleContainer(1);
	private final ContainerLevelAccess access;
	private final BlockPos blockPos;

	// Client constructor (via ExtendedMenuType)
	public DiscBurnerMenu(int syncId, Inventory playerInventory, BlockPos blockPos) {
		this(syncId, playerInventory, ContainerLevelAccess.NULL, blockPos);
	}

	// Server constructor
	public DiscBurnerMenu(int syncId, Inventory playerInventory, ContainerLevelAccess access, BlockPos blockPos) {
		super(ModMenus.DISC_BURNER, syncId);
		this.access = access;
		this.blockPos = blockPos;

		// Slot 0: Blank Disc or Custom Music Disc (single item only)
		this.addSlot(new Slot(this.discContainer, 0, DISC_SLOT_X, DISC_SLOT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return isBurnableDisc(stack);
			}

			@Override
			public int getMaxStackSize() {
				return 1;
			}
		});

		// Standard 3x9 player inventory + 1x9 hotbar
		this.addStandardInventorySlots(playerInventory, INV_START_X, INV_START_Y);
	}

	public static boolean isBurnableDisc(ItemStack stack) {
		return stack.is(ModItems.BLANK_DISC) || stack.is(ModItems.CUSTOM_MUSIC_DISC);
	}

	public BlockPos getBlockPos() {
		return blockPos;
	}

	public boolean hasBurnableDisc() {
		return isBurnableDisc(this.discContainer.getItem(0));
	}

	/**
	 * Replaces the disc in slot 0 with a freshly burned CustomMusicDiscItem.
	 * Called on the server thread after audio validation & storage succeed.
	 */
	public boolean burnDisc(CustomDiscData discData) {
		ItemStack current = this.discContainer.getItem(0);
		if (!isBurnableDisc(current)) {
			return false;
		}
		ItemStack burned = new ItemStack(ModItems.CUSTOM_MUSIC_DISC, 1);
		burned.set(ModComponents.CUSTOM_DISC_DATA, discData);
		this.discContainer.setItem(0, burned);
		this.broadcastChanges();
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot.hasItem()) {
			ItemStack stack = slot.getItem();
			result = stack.copy();
			if (index == DISC_SLOT_INDEX) {
				if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (isBurnableDisc(stack)) {
				if (!this.moveItemStackTo(stack, DISC_SLOT_INDEX, DISC_SLOT_INDEX + 1, false)) {
					return ItemStack.EMPTY;
				}
			} else {
				return ItemStack.EMPTY;
			}

			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return result;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		this.access.execute((level, pos) -> this.clearContainer(player, this.discContainer));
	}

	@Override
	public boolean stillValid(Player player) {
		return stillValid(this.access, player, ModBlocks.DISC_BURNER);
	}
}
