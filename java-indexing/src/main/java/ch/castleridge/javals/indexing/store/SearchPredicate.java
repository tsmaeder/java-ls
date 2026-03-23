package ch.castleridge.javals.indexing.store;

import java.util.Arrays;
import java.util.List;

/** Conjunction of substring conditions on named fields (AND). */
public record SearchPredicate(List<FieldCondition> conditions) {
    public SearchPredicate {
        conditions = List.copyOf(conditions);
    }

    public static SearchPredicate allOf(FieldCondition... conditions) {
        return new SearchPredicate(Arrays.asList(conditions));
    }
}
