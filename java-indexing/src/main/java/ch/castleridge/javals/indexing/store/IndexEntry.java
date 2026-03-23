package ch.castleridge.javals.indexing.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable index row: named string fields for substring indexing. */
public final class IndexEntry {
    private final Map<String, String> fields;

    public IndexEntry(Map<String, String> fields) {
        this.fields = Map.copyOf(new LinkedHashMap<>(fields));
    }

    public Map<String, String> fields() {
        return fields;
    }

    public String field(String name) {
        return fields.get(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IndexEntry that = (IndexEntry) o;
        return fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fields);
    }

    @Override
    public String toString() {
        return "IndexEntry" + fields;
    }
}
