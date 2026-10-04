package com.almostreliable.unified.recipe;

import com.almostreliable.unified.config.DuplicationConfig;
import com.almostreliable.unified.utils.JsonCompare;
import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** Groups recipes by necessary conditions for a duplicate match; collisions still use the full comparison. */
final class DuplicateGroups {
    static final class Index {
        final DuplicationConfig config;
        final RecipeLink[] links;
        final IdentityHashMap<RecipeLink, Integer> position;
        final ResourceLocation type;
        int[][] groupOf;          // per position: the positions (ascending) of the non-ignored recipes with the same key
        boolean crafting, sanitize, hasIgnored;
        Set<String> ignoredFields;

        private Index(DuplicationConfig config, RecipeLink[] links, IdentityHashMap<RecipeLink, Integer> position, ResourceLocation type) {
            this.config = config;
            this.links = links;
            this.position = position;
            this.type = type;
        }

        boolean handle(RecipeLink cur) {
            if (config.shouldIgnoreRecipe(cur)) return false;
            int pos = position.get(cur);
            JsonCompare.CompareContext ctx = config.getCompareContext(cur);
            if (groupOf == null) return firstPass(cur, pos, ctx);
            boolean found = false;
            for (int i : groupOf[pos]) {
                RecipeLink other = links[i];
                if (other != cur) found |= cur.handleDuplicate(other, ctx);
            }
            return found;
        }

        /**
         * Null when the list has a null entry, recipes of different types or of different crafting kinds (the original then
         * runs and throws or compares as before).
         */
        static Index prepare(DuplicationConfig config, List<RecipeLink> list) {
            RecipeLink[] links = list.toArray(new RecipeLink[0]);
            if (links.length == 0 || links[0] == null) return null;
            ResourceLocation type = links[0].getType();
            boolean crafting = links[0].isCraftingRecipe();
            IdentityHashMap<RecipeLink, Integer> position = new IdentityHashMap<>(links.length * 2);
            for (int i = 0; i < links.length; i++) {
                RecipeLink l = links[i];
                if (l == null || !type.equals(l.getType()) || l.isCraftingRecipe() != crafting) return null;
                if (position.put(l, i) != null) return null;   // the same link twice: not a list AU builds
            }
            Index idx = new Index(config, links, position, type);
            idx.crafting = crafting;
            return idx;
        }

        /**
         * The call for the first recipe of the list that is not ignored (cur0). For crafting types the outputs are computed
         * exactly as the original computes them during this call: cur0's at the first other non-ignored recipe, then each
         * other non-ignored recipe's in list order, each right before that recipe would be compared, so even an exception
         * from a comparison leaves the same outputs computed and the same warnings logged. Afterwards every non-ignored
         * recipe has its key and the groups are built for the later calls.
         */
        boolean firstPass(RecipeLink cur0, int pos0, JsonCompare.CompareContext ctx) {
            int n = links.length;
            boolean[] ign = new boolean[n];
            for (int i = 0; i < n; i++) {
                ign[i] = config.shouldIgnoreRecipe(links[i]);
            }
            JsonCompare.CompareSettings settings = config.getCompareSettings(type);
            ignoredFields = settings.getIgnoredFields();
            hasIgnored = settings.hasIgnoredFields();
            sanitize = settings.shouldSanitize();
            long[] k = new long[n];
            boolean found = false;
            if (crafting) {
                boolean started = false;
                long k0 = 0;
                for (int j = 0; j < n; j++) {
                    if (ign[j] || j == pos0) continue;
                    if (!started) {
                        output(cur0);
                        k0 = keyOf(cur0);
                        started = true;
                    }
                    output(links[j]);
                    k[j] = keyOf(links[j]);
                    if (k[j] == k0) {
                        found |= cur0.handleDuplicate(links[j], ctx);
                    }
                }
                k[pos0] = started ? k0 : 0;
            } else {
                for (int j = 0; j < n; j++) if (!ign[j]) k[j] = keyOf(links[j]);
                for (int j = 0; j < n; j++) {
                    if (ign[j] || j == pos0 || k[j] != k[pos0]) continue;
                    found |= cur0.handleDuplicate(links[j], ctx);
                }
            }
            HashMap<Long, List<Integer>> byKey = new HashMap<>();
            for (int i = 0; i < n; i++) if (!ign[i]) byKey.computeIfAbsent(k[i], x -> new ArrayList<>()).add(i);
            int[][] groups = new int[n][];
            for (List<Integer> members : byKey.values()) {
                int[] arr = new int[members.size()];
                for (int j = 0; j < arr.length; j++) arr[j] = members.get(j);
                for (int m : arr) groups[m] = arr;
            }
            groupOf = groups;
            return found;
        }

        private Object output(RecipeLink link) {
            return link.getCraftingRecipeOutput();
        }

        /** Hash of (compared field names, their values unless shouldSanitize, the output for crafting types): see the class comment. */
        private long keyOf(RecipeLink link) {
            JsonObject actual = link.getActual();
            long h = 0;
            int count = 0;
            for (Map.Entry<String, JsonElement> e : actual.entrySet()) {
                String f = e.getKey();
                if (hasIgnored && ignoredFields.contains(f)) continue;
                count++;
                long fh = mix(f.hashCode() * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
                h += mix(fh ^ (sanitize ? 0L : valueHash(e.getValue()) * 0xC2B2AE3D27D4EB4FL));
            }
            h = mix(h + count * 0x165667B19E3779F9L);
            if (crafting) h = mix(h ^ (System.identityHashCode(output(link)) * 0xD6E8FEB86659FD93L));
            return h;
        }

    }

    /** A hash of a JSON value that equal values (JsonElement.equals) always share; all numbers hash alike. */
    static long valueHash(JsonElement e) {
        if (e == null) return 0x3C6EF372FE94F82BL;
        if (e.isJsonNull()) return 0x1F83D9ABFB41BD6BL;
        if (e instanceof JsonPrimitive p) {
            if (p.isBoolean()) return p.getAsBoolean() ? 0x5BE0CD19137E2179L : 0x510E527FADE682D1L;
            if (p.isNumber()) return 0x6A09E667F3BCC908L;
            if (p.isString()) return mix(p.getAsString().hashCode() * 0x9E3779B97F4A7C15L + 0xBB67AE8584CAA73BL);
            return 0x7137449123EF65CDL;
        }
        if (e instanceof JsonArray a) {
            long h = 0xA54FF53A5F1D36F1L;
            for (JsonElement x : a) h = mix(h * 31 + valueHash(x));
            return mix(h + a.size());
        }
        if (e instanceof JsonObject o) {
            long h = 0x9B05688C2B3E6C1FL;
            for (Map.Entry<String, JsonElement> x : o.entrySet())
                h += mix(mix(x.getKey().hashCode() * 0x9E3779B97F4A7C15L) ^ valueHash(x.getValue()));
            return mix(h + o.size());
        }
        return 0x2B992DDFA23249D6L;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
