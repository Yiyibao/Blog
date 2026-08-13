package com.yubai.blog.kitchen;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.dish.DishEntity;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.kitchen.ShoppingListDtos.ItemDraft;
import com.yubai.blog.kitchen.ShoppingListDtos.ItemResponse;
import com.yubai.blog.kitchen.ShoppingListDtos.ShoppingListResponse;
import com.yubai.blog.kitchen.ShoppingListDtos.UpdateRequest;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShoppingListService {
    private static final Pattern QUANTITY =
            Pattern.compile("^\\s*(.*?)\\s+([0-9]+(?:[.,][0-9]+)?)\\s*([\\p{L}]+)?\\s*$");
    private static final Map<String, String> UNIT_ALIASES =
            Map.ofEntries(
                    Map.entry("g", "克"),
                    Map.entry("gram", "克"),
                    Map.entry("grams", "克"),
                    Map.entry("克", "克"),
                    Map.entry("kg", "千克"),
                    Map.entry("公斤", "千克"),
                    Map.entry("千克", "千克"),
                    Map.entry("ml", "毫升"),
                    Map.entry("毫升", "毫升"),
                    Map.entry("l", "升"),
                    Map.entry("升", "升"),
                    Map.entry("个", "个"),
                    Map.entry("只", "只"),
                    Map.entry("枚", "枚"),
                    Map.entry("片", "片"),
                    Map.entry("瓣", "瓣"),
                    Map.entry("根", "根"),
                    Map.entry("颗", "颗"),
                    Map.entry("粒", "粒"),
                    Map.entry("把", "把"),
                    Map.entry("勺", "勺"),
                    Map.entry("汤匙", "汤匙"),
                    Map.entry("tbsp", "汤匙"),
                    Map.entry("茶匙", "茶匙"),
                    Map.entry("tsp", "茶匙"),
                    Map.entry("包", "包"),
                    Map.entry("袋", "袋"),
                    Map.entry("罐", "罐"),
                    Map.entry("块", "块"),
                    Map.entry("段", "段"));

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final DailyMenuService dailyMenus;
    private final DishRepository dishes;

    public ShoppingListService(
            ShoppingListRepository lists,
            ShoppingListItemRepository items,
            DailyMenuService dailyMenus,
            DishRepository dishes) {
        this.lists = lists;
        this.items = items;
        this.dailyMenus = dailyMenus;
        this.dishes = dishes;
    }

    public static LocalDate parseWeekStart(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new KitchenBadRequestException("缺少 weekStart 参数（格式 YYYY-MM-DD）");
        }
        try {
            var date = LocalDate.parse(raw.trim());
            if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new KitchenBadRequestException("weekStart 必须是周一");
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw new KitchenBadRequestException("weekStart 格式不对，应为 YYYY-MM-DD");
        }
    }

    @Transactional
    public ShoppingListResponse getOrCreate(long ownerId, LocalDate weekStart) {
        return toResponse(findOrCreate(ownerId, weekStart));
    }

    @Transactional
    public ShoppingListResponse generate(long ownerId, LocalDate weekStart, String mutationKey) {
        var list = findOrCreate(ownerId, weekStart);
        if (sameMutation(list, mutationKey)) return toResponse(list);

        var existing = items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId());
        var generated = new LinkedHashMap<String, Aggregate>();
        for (int offset = 0; offset < 7; offset++) {
            var menu = dailyMenus.getMenu(weekStart.plusDays(offset));
            for (var menuItem : menu.items()) {
                if (menuItem.dishSlug() == null || menuItem.dishSlug().isBlank()) continue;
                dishes.findBySlugAndPublishedTrue(menuItem.dishSlug().trim())
                        .ifPresent(dish -> addDish(generated, dish));
            }
        }

        var drafts = new ArrayList<ItemDraft>();
        var generatedExisting =
                existing.stream()
                        .filter(item -> !item.isManual())
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        item -> identity(item.getNormalizedName(), item.getUnit()),
                                        Function.identity(),
                                        (a, b) -> a));
        for (var aggregate : generated.values()) {
            var previous = generatedExisting.get(aggregate.key());
            drafts.add(
                    new ItemDraft(
                            previous == null ? null : previous.getId(),
                            aggregate.displayName(),
                            aggregate.normalizedName(),
                            aggregate.quantity(),
                            aggregate.unit(),
                            aggregate.originalQuantity(),
                            aggregate.sourceRecipe(),
                            previous == null
                                    ? categoryFor(aggregate.normalizedName())
                                    : previous.getCategory(),
                            previous != null && previous.isChecked(),
                            false,
                            previous == null ? "" : previous.getNote()));
        }
        existing.stream()
                .filter(ShoppingListItemEntity::isManual)
                .forEach(item -> drafts.add(toDraft(item)));
        replaceLocked(list, drafts, mutationKey);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse update(
            long ownerId, UUID listId, UpdateRequest request, String mutationKey) {
        var list = lock(ownerId, listId);
        if (sameMutation(list, mutationKey)) return toResponse(list);
        checkVersion(list, request.expectedVersion());
        replaceLocked(list, request.items(), mutationKey, request.note());
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse clearChecked(
            long ownerId, UUID listId, long expectedVersion, String mutationKey) {
        var list = lock(ownerId, listId);
        if (sameMutation(list, mutationKey)) return toResponse(list);
        checkVersion(list, expectedVersion);
        var drafts =
                items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId()).stream()
                        .filter(item -> !item.isChecked())
                        .map(this::toDraft)
                        .toList();
        replaceLocked(list, drafts, mutationKey);
        return toResponse(list);
    }

    private ShoppingListEntity findOrCreate(long ownerId, LocalDate weekStart) {
        var existing = lists.findByOwnerIdAndWeekStart(ownerId, weekStart);
        if (existing.isPresent()) return existing.get();
        lists.insertIfAbsent(UUID.randomUUID(), ownerId, weekStart);
        return lists.findByOwnerIdAndWeekStart(ownerId, weekStart)
                .orElseThrow(() -> new IllegalStateException("购物清单创建后无法读取"));
    }

    private ShoppingListEntity lock(long ownerId, UUID listId) {
        return lists.findWithLockByIdAndOwnerId(listId, ownerId)
                .orElseThrow(() -> new NotFoundException("购物清单不存在或不属于当前用户"));
    }

    private void checkVersion(ShoppingListEntity list, long expectedVersion) {
        if (list.getVersion() != expectedVersion) throw new ShoppingListVersionConflictException();
    }

    private boolean sameMutation(ShoppingListEntity list, String mutationKey) {
        return mutationKey != null
                && !mutationKey.isBlank()
                && mutationKey.equals(list.getLastMutationKey());
    }

    private void replaceLocked(
            ShoppingListEntity list, List<ItemDraft> drafts, String mutationKey) {
        replaceLocked(list, drafts, mutationKey, list.getNote());
    }

    private void replaceLocked(
            ShoppingListEntity list, List<ItemDraft> drafts, String mutationKey, String note) {
        var existing = new HashMap<UUID, ShoppingListItemEntity>();
        items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId())
                .forEach(item -> existing.put(item.getId(), item));
        var retained = new java.util.HashSet<UUID>();
        var toSave = new ArrayList<ShoppingListItemEntity>();
        int sortOrder = 0;
        for (var draft : drafts) {
            var normalized = normalizeName(draft.normalizedName(), draft.displayName());
            var unit = normalizeUnit(draft.unit());
            var item = draft.id() == null ? null : existing.get(draft.id());
            if (item == null) {
                item =
                        ShoppingListItemEntity.create(
                                list.getId(),
                                normalized,
                                clean(draft.displayName(), 160),
                                draft.quantity(),
                                unit,
                                clean(draft.originalQuantity(), 240),
                                clean(draft.sourceRecipe(), 500),
                                category(draft.category()),
                                draft.checked(),
                                draft.manual(),
                                clean(draft.note(), 240),
                                sortOrder);
            } else {
                item.update(
                        normalized,
                        clean(draft.displayName(), 160),
                        draft.quantity(),
                        unit,
                        clean(draft.originalQuantity(), 240),
                        clean(draft.sourceRecipe(), 500),
                        category(draft.category()),
                        draft.checked(),
                        draft.manual(),
                        clean(draft.note(), 240),
                        sortOrder);
            }
            retained.add(item.getId());
            toSave.add(item);
            sortOrder++;
        }
        existing.values().stream()
                .filter(item -> !retained.contains(item.getId()))
                .forEach(items::delete);
        items.saveAll(toSave);
        list.updateNote(clean(note, 500));
        if (mutationKey != null && !mutationKey.isBlank())
            list.markMutation(clean(mutationKey, 160));
        lists.saveAndFlush(list);
    }

    private void addDish(Map<String, Aggregate> aggregates, DishEntity dish) {
        for (var raw : dish.getIngredients()) {
            var parsed = parseIngredient(raw);
            if (parsed == null) continue;
            var key = identity(parsed.normalizedName(), parsed.unit());
            aggregates.compute(
                    key,
                    (ignored, current) ->
                            current == null
                                    ? new Aggregate(
                                            parsed.normalizedName(),
                                            parsed.displayName(),
                                            parsed.quantity(),
                                            parsed.unit(),
                                            parsed.originalQuantity(),
                                            dish.getName())
                                    : current.add(
                                            parsed.quantity(),
                                            parsed.originalQuantity(),
                                            dish.getName()));
        }
    }

    private ParsedIngredient parseIngredient(String raw) {
        var original = raw == null ? "" : raw.trim();
        if (original.isBlank()) return null;
        Matcher matcher = QUANTITY.matcher(original);
        if (!matcher.matches()) {
            var name = normalizeName(original, original);
            return new ParsedIngredient(name, original, null, "", original);
        }
        var name = matcher.group(1).trim();
        if (name.isBlank()) name = original;
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(matcher.group(2).replace(',', '.'));
        } catch (NumberFormatException exception) {
            quantity = null;
        }
        return new ParsedIngredient(
                normalizeName(name, name),
                name,
                quantity,
                normalizeUnit(matcher.group(3)),
                original);
    }

    private static String normalizeName(String value, String fallback) {
        var result =
                (value == null || value.isBlank() ? fallback : value)
                        .trim()
                        .replaceAll("[\\s　]+", "")
                        .toLowerCase(Locale.ROOT);
        return result.isBlank() ? "未命名食材" : result;
    }

    private static String normalizeUnit(String value) {
        if (value == null || value.isBlank()) return "";
        var key = value.trim().toLowerCase(Locale.ROOT);
        return UNIT_ALIASES.getOrDefault(key, value.trim());
    }

    private static String identity(String normalizedName, String unit) {
        return normalizeName(normalizedName, normalizedName) + "|" + normalizeUnit(unit);
    }

    private static String categoryFor(String normalizedName) {
        if (normalizedName.matches(".*(盐|糖|酱|醋|油|料酒|胡椒|淀粉).*")) return "调味料";
        if (normalizedName.matches(".*(肉|鸡|鸭|鱼|虾|牛|猪|蛋|豆腐).*")) return "肉蛋豆制品";
        if (normalizedName.matches(".*(菜|葱|姜|蒜|番茄|土豆|胡萝卜|菌).*")) return "蔬菜";
        return "未分类";
    }

    private static String category(String value) {
        return value == null || value.isBlank() ? "未分类" : clean(value, 60);
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        var trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private ItemDraft toDraft(ShoppingListItemEntity item) {
        return new ItemDraft(
                item.getId(),
                item.getDisplayName(),
                item.getNormalizedName(),
                item.getQuantity(),
                item.getUnit(),
                item.getOriginalQuantity(),
                item.getSourceRecipe(),
                item.getCategory(),
                item.isChecked(),
                item.isManual(),
                item.getNote());
    }

    private ShoppingListResponse toResponse(ShoppingListEntity list) {
        var responseItems =
                items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId()).stream()
                        .map(
                                item ->
                                        new ItemResponse(
                                                item.getId(),
                                                item.getDisplayName(),
                                                item.getNormalizedName(),
                                                item.getQuantity(),
                                                item.getUnit(),
                                                item.getOriginalQuantity(),
                                                item.getSourceRecipe(),
                                                item.getCategory(),
                                                item.isChecked(),
                                                item.isManual(),
                                                item.getNote(),
                                                item.getSortOrder(),
                                                item.getCreatedAt()))
                        .toList();
        return new ShoppingListResponse(
                list.getId(),
                list.getWeekStart(),
                list.getNote(),
                list.getVersion(),
                list.getCreatedAt(),
                list.getUpdatedAt(),
                responseItems);
    }

    private record ParsedIngredient(
            String normalizedName,
            String displayName,
            BigDecimal quantity,
            String unit,
            String originalQuantity) {}

    private record Aggregate(
            String normalizedName,
            String displayName,
            BigDecimal quantity,
            String unit,
            String originalQuantity,
            String sourceRecipe) {
        Aggregate add(BigDecimal nextQuantity, String nextOriginal, String nextRecipe) {
            var total =
                    quantity == null || nextQuantity == null ? null : quantity.add(nextQuantity);
            return new Aggregate(
                    normalizedName,
                    displayName,
                    total,
                    unit,
                    originalQuantity + "；" + nextOriginal,
                    sourceRecipe.contains(nextRecipe)
                            ? sourceRecipe
                            : sourceRecipe + "、" + nextRecipe);
        }

        String key() {
            return identity(normalizedName, unit);
        }
    }
}
