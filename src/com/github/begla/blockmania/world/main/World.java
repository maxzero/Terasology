/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world.main;

import com.github.begla.blockmania.audio.AudioManager;
import com.github.begla.blockmania.blueprints.BlockGrid;
import com.github.begla.blockmania.configuration.ConfigurationManager;
import com.github.begla.blockmania.game.Blockmania;
import com.github.begla.blockmania.generators.ChunkGeneratorTerrain;
import com.github.begla.blockmania.rendering.interfaces.RenderableObject;
import com.github.begla.blockmania.rendering.manager.ShaderManager;
import com.github.begla.blockmania.rendering.manager.TextureManager;
import com.github.begla.blockmania.rendering.particles.BlockParticleEmitter;
import com.github.begla.blockmania.world.characters.MobManager;
import com.github.begla.blockmania.world.characters.Player;
import com.github.begla.blockmania.world.chunk.Chunk;
import com.github.begla.blockmania.world.chunk.ChunkMesh;
import com.github.begla.blockmania.world.chunk.ChunkUpdateManager;
import com.github.begla.blockmania.world.horizon.Skysphere;
import com.github.begla.blockmania.world.physics.BulletPhysicsRenderer;
import javolution.util.FastList;
import org.lwjgl.opengl.GL20;
import org.newdawn.slick.openal.SoundStore;

import javax.vecmath.Vector3f;
import java.util.Collections;

import static org.lwjgl.opengl.GL11.glColorMask;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World implements RenderableObject {

    private static final long UPDATE_GAP = 1000 / (Integer) ConfigurationManager.getInstance().getConfig().get("System.chunkRequestsPerSecond");

    /* WORLD PROVIDER */
    private WorldProvider _worldProvider;

    /* PLAYER */
    private Player _player;

    /* CHUNKS */
    private FastList<Chunk> _chunksInProximity = new FastList<Chunk>();
    private long _lastChunkUpdate = Blockmania.getInstance().getTime();

    /* MOBS */
    private MobManager _mobManager;

    /* PARTICLE EMITTERS */
    private final BlockParticleEmitter _blockParticleEmitter = new BlockParticleEmitter(this);

    /* HORIZON */
    private final Skysphere _skysphere;

    /* WATER AND LAVA ANIMATION */
    private int _tick = 0;
    private long _lastTick;

    /* UPDATING */
    private final ChunkUpdateManager _chunkUpdateManager;
    private int prevChunkPosX = 0, prevChunkPosZ = 0;

    /* EVENTS */
    private final WorldTimeEventManager _worldTimeEventManager;

    /* PHYSICS */
    private BulletPhysicsRenderer _bulletPhysicsRenderer;

    /* BLOCK GRID */
    private final BlockGrid _blockGrid;

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     */
    public World(String title, String seed) {
        _worldProvider = new LocalWorldProvider(title, seed);
        _skysphere = new Skysphere(this);
        _chunkUpdateManager = new ChunkUpdateManager();
        _worldTimeEventManager = new WorldTimeEventManager(_worldProvider);
        _mobManager = new MobManager();
        _blockGrid = new BlockGrid(this);
        _bulletPhysicsRenderer = new BulletPhysicsRenderer(this);

        createMusicTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @return True if the list was changed
     */
    private boolean updateChunksInProximity() {
        if ((Blockmania.getInstance().getTime() - _lastChunkUpdate < UPDATE_GAP)) {
            return false;
        }

        _lastChunkUpdate = Blockmania.getInstance().getTime();

        if (prevChunkPosX != calcPlayerChunkOffsetX() || prevChunkPosZ != calcPlayerChunkOffsetZ()) {

            prevChunkPosX = calcPlayerChunkOffsetX();
            prevChunkPosZ = calcPlayerChunkOffsetZ();

            FastList<Chunk> newChunksInProximity = new FastList<Chunk>();

            int viewingDistanceX = (Integer) ConfigurationManager.getInstance().getConfig().get("Graphics.viewingDistanceX");
            int viewingDistanceZ = (Integer) ConfigurationManager.getInstance().getConfig().get("Graphics.viewingDistanceZ");

            for (int x = -(viewingDistanceX / 2); x < (viewingDistanceX / 2); x++) {
                for (int z = -(viewingDistanceZ / 2); z < (viewingDistanceZ / 2); z++) {
                    Chunk c = _worldProvider.getChunkProvider().loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                    newChunksInProximity.add(c);
                }
            }

            Collections.sort(newChunksInProximity);
            _chunksInProximity = newChunksInProximity;
            return true;
        }

        return false;
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void createMusicTimeEvents() {
        // SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.01, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Sunrise").playAsMusic(1.0f, 1.0f, false);
            }
        });

        // AFTERNOON
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.33, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Afternoon").playAsMusic(1.0f, 1.0f, false);
            }
        });

        // SUNSET
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.44, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Sunset").playAsMusic(1.0f, 1.0f, false);
            }
        });
    }

    /**
     * Fetches the currently visible chunks (in sight of the player).
     *
     * @return The visible chunks
     */
    public FastList<Chunk> fetchVisibleChunks() {
        FastList<Chunk> result = new FastList<Chunk>();
        FastList<Chunk> chunksInProximity = _chunksInProximity;

        for (FastList.Node<Chunk> n = chunksInProximity.head(), end = chunksInProximity.tail(); (n = n.getNext()) != end; ) {
            Chunk c = n.getValue();

            if (isChunkVisible(c)) {
                c.setVisible(true);
                result.add(c);
                continue;
            }

            c.setVisible(false);
        }

        return result;
    }

    /**
     * Renders the world.
     */
    public void render() {
        /* SKYSPHERE */
        _player.getActiveCamera().lookThroughNormalized();
        _skysphere.render();

        /* WORLD RENDERING */
        _player.getActiveCamera().lookThrough();

        _player.render();
        renderChunksAndEntities();

        /* PARTICLE EFFECTS */
        _blockParticleEmitter.render();
        _blockGrid.render();
    }


    /**
     * Renders all chunks that are currently in the player's field of view.
     */
    private void renderChunksAndEntities() {

        ShaderManager.getInstance().enableShader("chunk");
        TextureManager.getInstance().bindTexture("terrain");

        int daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "daylight");
        int swimming = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "swimming");
        int tick = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "tick");

        GL20.glUniform1f(tick, _tick);
        GL20.glUniform1f(daylight, getDaylight());
        GL20.glUniform1i(swimming, _player.isHeadUnderWater() ? 1 : 0);

        FastList<Chunk> visibleChunks = fetchVisibleChunks();

        // OPAQUE ELEMENTS
        for (FastList.Node<Chunk> n = visibleChunks.head(), end = visibleChunks.tail(); (n = n.getNext()) != end; ) {
            Chunk c = n.getValue();

            c.render(ChunkMesh.RENDER_TYPE.OPAQUE);

            if ((Boolean) ConfigurationManager.getInstance().getConfig().get("System.Debug.chunkOutlines")) {
                c.getAABB().render();
            }
        }

        _mobManager.renderAll();

        ShaderManager.getInstance().enableShader("block");
        _bulletPhysicsRenderer.render();
        ShaderManager.getInstance().enableShader("chunk");

        // ANIMATED LAVA
        TextureManager.getInstance().bindTexture("custom_lava_still");

        for (FastList.Node<Chunk> n = visibleChunks.head(), end = visibleChunks.tail(); (n = n.getNext()) != end; ) {
            Chunk c = n.getValue();
            c.render(ChunkMesh.RENDER_TYPE.LAVA);
        }

        TextureManager.getInstance().bindTexture("terrain");

        // BILLBOARDS AND TRANSLUCENT ELEMENTS
        for (FastList.Node<Chunk> n = visibleChunks.head(), end = visibleChunks.tail(); (n = n.getNext()) != end; ) {
            Chunk c = n.getValue();
            c.render(ChunkMesh.RENDER_TYPE.BILLBOARD_AND_TRANSLUCENT);
        }

        TextureManager.getInstance().bindTexture("custom_water_still");

        for (int i = 0; i < 2; i++) {
            // ANIMATED WATER
            for (FastList.Node<Chunk> n = visibleChunks.head(), end = visibleChunks.tail(); (n = n.getNext()) != end; ) {
                Chunk c = n.getValue();

                if (i == 0) {
                    glColorMask(false, false, false, false);
                } else {
                    glColorMask(true, true, true, true);
                }

                c.render(ChunkMesh.RENDER_TYPE.WATER);
            }
        }

        ShaderManager.getInstance().enableShader(null);
    }

    public void update() {
        // Make sure the chunks near the player are generated first
        _worldProvider.getRenderingReferencePoint().set(_player.getPosition());

        updateTick();

        _skysphere.update();
        _player.update();
        _mobManager.updateAll();

        _bulletPhysicsRenderer.resetChunks();

        // Update the list of relevant chunks
        updateChunksInProximity();

        for (int i = 0; i < 16 && i < _chunksInProximity.size(); i++) {
            /* PHYSICS */
            if (_chunksInProximity.get(i).getActiveChunkMesh() != null) {
                if (_chunksInProximity.get(i).getActiveChunkMesh()._bulletMeshShape != null) {
                    Vector3f position = new Vector3f(_chunksInProximity.get(i).getPosition());
                    position.x *= Chunk.getChunkDimensionX();
                    position.y *= Chunk.getChunkDimensionY();
                    position.z *= Chunk.getChunkDimensionZ();

                    _bulletPhysicsRenderer.addStaticChunk(position, _chunksInProximity.get(i).getActiveChunkMesh()._bulletMeshShape);
                }
            }
            /* ------ */
        }

        _bulletPhysicsRenderer.update();

        // Update visible chunks
        for (FastList.Node<Chunk> n = _chunksInProximity.head(), end = _chunksInProximity.tail(); (n = n.getNext()) != end; ) {
            if (n.getValue().isVisible()) {
                if (n.getValue().isDirty() || n.getValue().isLightDirty()) {
                    _chunkUpdateManager.queueChunkUpdate(n.getValue(), ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
                    continue;
                }

                n.getValue().update();
            }
        }

        // Update the particle emitters
        _blockParticleEmitter.update();

        // Free unused space
        _worldProvider.getChunkProvider().flushCache();

        // And finally fire any active events
        _worldTimeEventManager.fireWorldTimeEvents();

        // Does nothing at the moment
        //_blockGrid.update();
    }

    /**
     * Updates the tick value used for animating the textures.
     */
    private void updateTick() {
        _tick++;
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.getChunkDimensionY() - 1; y >= 0; y--) {
            if (_worldProvider.getBlock(x, y, z) != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Chunk.getChunkDimensionX());
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Chunk.getChunkDimensionZ());
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(Player p) {
        if (_player != null) {
            _player.unregisterObserver(_chunkUpdateManager);
            _player.unregisterObserver(_bulletPhysicsRenderer);
        }

        _player = p;
        _player.registerObserver(_chunkUpdateManager);
        _player.registerObserver(_bulletPhysicsRenderer);

        _player.setSpawningPoint(_worldProvider.nextSpawningPoint());
        _player.reset();
        _player.respawn();
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        _worldProvider.dispose();
        AudioManager.getInstance().stopAllSounds();
    }

    @Override
    public String toString() {
        return String.format("world (biome: %s, time: %f, sun: %f, cache: %d, cu-duration: %fms, seed: \"%s\", title: \"%s\")", getActiveBiome(), _worldProvider.getTime(), _skysphere.getSunPosAngle(), _worldProvider.getChunkProvider().size(), _chunkUpdateManager.getAverageUpdateDuration(), _worldProvider.getSeed(), _worldProvider.getTitle());
    }

    public Player getPlayer() {
        return _player;
    }

    public boolean isChunkVisible(Chunk c) {
        return _player.getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public float getDaylight() {
        return _skysphere.getDaylight();
    }

    public BlockParticleEmitter getBlockParticleEmitter() {
        return _blockParticleEmitter;
    }

    public ChunkGeneratorTerrain.BIOME_TYPE getActiveBiome() {
        return _worldProvider.getActiveBiome((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveHumidity() {
        return _worldProvider.getHumidityAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveTemperature() {
        return _worldProvider.getTemperatureAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public WorldProvider getWorldProvider() {
        return _worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return _blockGrid;
    }

    public MobManager getMobManager() {
        return _mobManager;
    }

    public BulletPhysicsRenderer getRigidBlocksRenderer() {
        return _bulletPhysicsRenderer;
    }
}
