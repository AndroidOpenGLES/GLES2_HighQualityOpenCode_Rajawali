/**
 * Copyright 2013 Dennis Ippel
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.rajawali3d.loader;

import java.io.File;

import org.rajawali3d.Object3D;
import org.rajawali3d.materials.textures.TextureManager;
import org.rajawali3d.renderer.Renderer;

import android.content.res.Resources;

public abstract class AMeshLoader extends ALoader implements IMeshLoader {

    protected TextureManager mTextureManager;

    protected Object3D mRootObject;

    public AMeshLoader(File file) {
        super(file);
        mRootObject = new Object3D();
    }

    public AMeshLoader(String fileOnSDCard) {
        super(fileOnSDCard);
        mRootObject = new Object3D();
    }

    public AMeshLoader(Renderer renderer, String fileOnSDCard) {
        super(renderer, fileOnSDCard);
        mRootObject = new Object3D();
    }

    public AMeshLoader(Resources resources, TextureManager textureManager, int resourceId) {
        super(resources, resourceId);
        mTextureManager = textureManager;
        mRootObject = new Object3D();
    }

    public AMeshLoader(Renderer renderer, File file) {
        super(renderer, file);
        mRootObject = new Object3D();
    }

    /**
     * 解析文件
     *
     * @return
     * @throws ParsingException
     */
    public AMeshLoader parse() throws ParsingException {
        super.parse();
        return this;
    }

    /**
     * 返回从文件读取的Object3D
     *
     * @return
     */
    public Object3D getParsedObject() {
        return mRootObject;
    }

    /**
     * 材质类对象
     */
    protected class MaterialDef {
        // 材质对象名称
        public String name;
        // 环境光
        public int ambientColor;
        // 散射光
        public int diffuseColor;
        // 镜面光
        public int specularColor;
        // 高光调整参数
        public float specularCoefficient;
        // 溶解度，为0时完全透明，1完全不透明
        public float alpha = 1f;
        // map_Ka，map_Kd，map_Ks：材质的环境（ambient），散射（diffuse）和镜面（specular）贴图
        public String ambientTexture;
        public String diffuseTexture;
        public String specularColorTexture;
        public String specularHighlightTexture;
        public String alphaTexture;
        public String bumpTexture;
    }
}
