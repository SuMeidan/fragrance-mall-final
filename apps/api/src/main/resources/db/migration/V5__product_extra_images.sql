ALTER TABLE product
    ADD COLUMN scent_image_path VARCHAR(240) NULL AFTER image_path,
    ADD COLUMN scene_image_path VARCHAR(240) NULL AFTER scent_image_path;
