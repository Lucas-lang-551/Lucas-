package com.rocketcraft;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RocketCraft implements ModInitializer {
    static final String TAG = "rc_ball";
    final Map<UUID, Vec3d> vel = new HashMap<>();
    final Map<UUID, Vec3d> lastPos = new HashMap<>();
    final Box[] goals = new Box[2];
    final int[] score = new int[2];
    Vec3d center = null;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            LiteralArgumentBuilder<ServerCommandSource> rc = CommandManager.literal("rc");
            rc.then(CommandManager.literal("ball").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                Vec3d pos = center != null ? center : p.getPos().add(0, 2, 0);
                spawnBall(p.getWorld(), pos);
                return 1;
            }));
            rc.then(CommandManager.literal("center").executes(c -> {
                center = c.getSource().getPlayerOrThrow().getPos().add(0, 2, 0);
                c.getSource().sendFeedback(() -> Text.literal("Centro do campo definido."), false);
                return 1;
            }));
            rc.then(CommandManager.literal("boost").executes(c -> {
                c.getSource().getPlayerOrThrow().giveItemStack(new ItemStack(Items.BLAZE_POWDER, 16));
                return 1;
            }));
            rc.then(CommandManager.literal("reset").executes(c -> {
                score[0] = score[1] = 0;
                c.getSource().sendFeedback(() -> Text.literal("Placar zerado."), true);
                return 1;
            }));
            for (int i = 0; i < 2; i++) {
                final int team = i;
                String name = i == 0 ? "blue" : "orange";
                rc.then(CommandManager.literal("goal").then(CommandManager.literal(name)
                    .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                    .then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(c -> {
                        BlockPos a = BlockPosArgumentType.getBlockPos(c, "from");
                        BlockPos b = BlockPosArgumentType.getBlockPos(c, "to");
                        goals[team] = new Box(Vec3d.of(a), Vec3d.of(b)).stretch(1, 1, 1).expand(0, 0, 0);
                        c.getSource().sendFeedback(() -> Text.literal("Gol " + name + " definido."), false);
                        return 1;
                    })))));
            }
            d.register(rc);
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && entity.getCommandTags().contains(TAG)) {
                Vec3d look = player.getRotationVec(1f);
                vel.put(entity.getUuid(), new Vec3d(look.x * 1.4, 0.35 + look.y * 0.6, look.z * 1.4));
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack s = player.getStackInHand(hand);
            if (!world.isClient && s.isOf(Items.BLAZE_POWDER) && !player.getItemCooldownManager().isCoolingDown(s)) {
                Vec3d look = player.getRotationVec(1f).multiply(1.3);
                player.setVelocity(look.x, Math.max(look.y, 0.2), look.z);
                player.velocityModified = true;
                ((ServerWorld) world).spawnParticles(ParticleTypes.FLAME, player.getX(), player.getY(), player.getZ(), 20, 0.3, 0.1, 0.3, 0.05);
                player.getItemCooldownManager().set(s, 15);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        ServerTickEvents.END_WORLD_TICK.register(this::tick);
    }

    void spawnBall(ServerWorld w, Vec3d pos) {
        SlimeEntity b = EntityType.SLIME.create(w, SpawnReason.COMMAND);
        if (b == null) return;
        b.setSize(4, true);
        b.setAiDisabled(true);
        b.setNoGravity(true);
        b.setInvulnerable(true);
        b.setSilent(true);
        b.setPersistent();
        b.addCommandTag(TAG);
        b.setCustomName(Text.literal("Bola"));
        b.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
        w.spawnEntity(b);
        vel.put(b.getUuid(), Vec3d.ZERO);
    }

    void tick(ServerWorld w) {
        for (SlimeEntity b : w.getEntitiesByType(EntityType.SLIME, e -> e.getCommandTags().contains(TAG))) {
            Vec3d v = vel.getOrDefault(b.getUuid(), Vec3d.ZERO);

            for (PlayerEntity p : w.getPlayers()) {
                Vec3d prev = lastPos.getOrDefault(p.getUuid(), p.getPos());
                Vec3d pv = p.getPos().subtract(prev);
                if (p.getBoundingBox().expand(0.2).intersects(b.getBoundingBox())) {
                    Vec3d dir = b.getPos().subtract(p.getPos()).multiply(1, 0, 1).normalize();
                    double speed = Math.min(2.0, pv.horizontalLength() * 2.5 + 0.35);
                    v = new Vec3d(dir.x * speed, Math.max(v.y, 0.15), dir.z * speed);
                }
            }

            v = v.add(0, -0.04, 0).multiply(0.99, 0.98, 0.99);
            Vec3d before = b.getPos();
            b.move(MovementType.SELF, v);
            Vec3d moved = b.getPos().subtract(before);

            if (Math.abs(moved.y) < Math.abs(v.y) * 0.5) v = new Vec3d(v.x * 0.97, Math.abs(v.y) < 0.12 ? 0 : -v.y * 0.75, v.z * 0.97);
            if (Math.abs(moved.x) < Math.abs(v.x) * 0.5) v = new Vec3d(-v.x * 0.7, v.y, v.z);
            if (Math.abs(moved.z) < Math.abs(v.z) * 0.5) v = new Vec3d(v.x, v.y, -v.z * 0.7);
            vel.put(b.getUuid(), v);

            for (int t = 0; t < 2; t++) {
                if (goals[t] != null && goals[t].contains(b.getPos())) {
                    int scorer = 1 - t;
                    score[scorer]++;
                    w.getServer().getPlayerManager().broadcast(Text.literal("⚽ GOOOL do " + (scorer == 0 ? "§9AZUL" : "§6LARANJA")
                        + "§r! Azul " + score[0] + " x " + score[1] + " Laranja"), false);
                    Vec3d c = center != null ? center : before.add(0, 3, 0);
                    b.refreshPositionAndAngles(c.x, c.y, c.z, 0, 0);
                    vel.put(b.getUuid(), Vec3d.ZERO);
                }
            }
        }
        for (PlayerEntity p : w.getPlayers()) lastPos.put(p.getUuid(), p.getPos());
    }
                }
