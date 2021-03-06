package io.quarkus.it.mongodb.panache.test;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TestImperativeEntity extends PanacheMongoEntity {
    public String title;
    public String category;
    public String description;

    public TestImperativeEntity() {
    }

    public TestImperativeEntity(String title, String category, String description) {
        this.title = title;
        this.category = category;
        this.description = description;
    }
}
