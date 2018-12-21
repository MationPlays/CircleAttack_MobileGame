package com.mygdx.circleattack;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

public class CircleAttack extends ApplicationAdapter {
	SpriteBatch batch;
	Texture img;
	Texture squr;
	Square player;
	Sprite square;

	
	@Override
	public void create () {
		batch = new SpriteBatch();
		img = new Texture("badlogic.jpg");
		squr = new Texture("Assets/Square_Blue.png");
		square = new Sprite(squr, 100, 100, 80, 80);
	}

	@Override
	public void render () {
		Gdx.gl.glClearColor(1, 0, 0, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		batch.begin();
		batch.draw(squr, 100, 100);
		batch.end();
	}

	public void handleInput(float dt) {
		if(square != null){
			if(Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
				square.setPosition(square.getX() + (-200 * dt), square.getY());
			} else if(Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
				square.setPosition(square.getX() + (+200 * dt), square.getY());
			}
		}
	}
	
	@Override
	public void dispose () {
		batch.dispose();
		img.dispose();
		squr.dispose();
	}
}
