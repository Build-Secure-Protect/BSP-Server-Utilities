package com.mrgregles.bsp_server_utilities.mixin;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies every mixin of the config as listed, and checks the lag profiler's hooks went in (SU-151). Those injects are
 * {@code require = 0}, so a server whose code differs (a Mohist build that patches the methods) starts without them; this
 * finds which hooks are missing once their class is loaded and says so through the system property {@value #MISSING}
 * (a property, not a field: this class is loaded before the game's own class loader takes over). The profiler reads it,
 * refuses a sample and logs once. A mixin whose target class is missing is never applied, so nothing here would run for
 * it: the lag mixins expected ({@value #EXPECTED}) and those applied ({@value #APPLIED}) are said too, and the profiler
 * counts one never applied as missing (audit of SU-153).
 */
public final class LagMixinPlugin implements IMixinConfigPlugin {
    /** The hooks missing so far, separated by spaces; unset when none is. */
    public static final String MISSING = "bsp_server_utilities.lag.missing";
    /** The lag mixins expected, and those applied so far, separated by spaces. */
    public static final String EXPECTED = "bsp_server_utilities.lag.expected", APPLIED = "bsp_server_utilities.lag.applied";
    private static final String PREFIX = "bspSu$lag";
    private static final Map<String, List<String>> HOOKS = Map.of(
            "BlockEntityTickLagMixin", List.of("BlockEntityHead", "BlockEntityReturn"),
            "LevelLagMixin", List.of("BlockEntitiesHead", "BlockEntitiesReturn"),
            "ServerLevelLagMixin", List.of("EntityHead", "EntityReturn", "ChunkHead", "ChunkReturn", "BlockHead", "BlockReturn", "FluidHead", "FluidReturn"));

    @Override
    public void onLoad(String mixinPackage) {
        System.setProperty(EXPECTED, String.join(" ", new java.util.TreeSet<>(HOOKS.keySet())));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        List<String> expected = HOOKS.get(name);
        if (expected == null) {
            return;
        }
        String applied = System.getProperty(APPLIED, "");
        System.setProperty(APPLIED, applied.isEmpty() ? name : applied + " " + name);
        // a handler is renamed when merged (handler$xxx$bspSu$lag...); an inject that went in calls it from the target method
        Set<String> found = new HashSet<>();
        for (MethodNode m : targetClass.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(targetClass.name)) {
                    int at = call.name.indexOf(PREFIX);
                    if (at >= 0 && !m.name.contains(PREFIX)) {
                        found.add(call.name.substring(at + PREFIX.length()));
                    }
                }
            }
        }
        StringBuilder missing = new StringBuilder(System.getProperty(MISSING, ""));
        for (String e : expected) {
            if (!found.contains(e)) {
                missing.append(missing.length() == 0 ? "" : " ").append(targetClassName.substring(targetClassName.lastIndexOf('.') + 1)).append('.').append(e);
            }
        }
        if (missing.length() > 0) {
            System.setProperty(MISSING, missing.toString());
        }
    }
}
