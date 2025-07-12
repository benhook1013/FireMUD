CREATE TABLE crafting_recipes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    result_item_id BIGINT NOT NULL REFERENCES items(id),
    result_quantity INT NOT NULL
);

CREATE TABLE crafting_ingredients (
    recipe_id BIGINT NOT NULL REFERENCES crafting_recipes(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    PRIMARY KEY (recipe_id, item_id)
);
