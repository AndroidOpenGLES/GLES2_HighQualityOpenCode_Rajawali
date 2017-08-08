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

import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.rajawali3d.Object3D;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.methods.DiffuseMethod;
import org.rajawali3d.materials.methods.SpecularMethod;
import org.rajawali3d.materials.textures.ATexture.TextureException;
import org.rajawali3d.materials.textures.Etc1Texture;
import org.rajawali3d.materials.textures.NormalMapTexture;
import org.rajawali3d.materials.textures.SpecularMapTexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.materials.textures.TextureManager;
import org.rajawali3d.renderer.Renderer;
import org.rajawali3d.util.RajLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.StringTokenizer;

/**
 * The most important thing is that the model should be triangulated. Rajawali doesn�t accept quads, only tris. In Blender, this is an option you can select in the exporter. In a program like MeshLab, this is done automatically.
 * At the moment, Rajawali also doesn�t support per-face textures. This is on the todo list.
 * <p>
 * The options that should be checked when exporting from blender are:
 * <ul>
 * <li>Apply Modifiers
 * <li>Include Normals
 * <li>Include UVs
 * <li>Write Materials (if applicable)
 * <li>Triangulate Faces
 * <li>Objects as OBJ Objects
 * </ul>
 * <p>
 * The files should be written to your �res/raw� folder in your ADT project. Usually you�ll get errors in the console when you do this. The Android SDK ignores file extensions so it�ll regard the .obj and .mtl files as duplicates. The way to fix this is to rename the files. For instance:
 * - myobject.obj > myobject_obj
 * - myobject.mtl > myobject_mtl
 * The parser replaces any dots in file names, so this should be picked up automatically by the parser. Path fragments in front of file names (also texture paths) are discarded so you can leave them as is.
 * <p>
 * The texture file paths in the .mtl files are stripped off periods and path fragments as well. The textures need to be placed in the res/drawable-nodpi folder.
 * <p>
 * If it still throws errors check if there are any funny characters or unsupported texture formats (like bmp).
 * <p>
 * Just as a reminder, here�s the code that takes care of the parsing:
 * <pre>
 * {@code
 * ObjParser objParser = new ObjParser(mContext.getResources(), mTextureManager, R.raw.myobject_obj);
 * objParser.parse();
 * BaseObject3D mObject = objParser.getParsedObject();
 * mObject.setLight(mLight);
 * addChild(mObject);
 * }
 * </pre>
 *
 * @author dennis.ippel
 */
public class LoaderOBJ extends AMeshLoader {
    protected final String VERTEX = "v";
    protected final String FACE = "f";
    protected final String TEXCOORD = "vt";
    protected final String NORMAL = "vn";
    protected final String OBJECT = "o";
    protected final String GROUP = "g";
    protected final String MATERIAL_LIB = "mtllib";
    protected final String USE_MATERIAL = "usemtl";
    protected final String NEW_MATERIAL = "newmtl";
    protected final String DIFFUSE_COLOR = "Kd";
    protected final String DIFFUSE_TEX_MAP = "map_Kd";

    // 是否 需要重命名材质文件
    private boolean mNeedToRenameMtl = true;

    public LoaderOBJ(Renderer renderer, String fileOnSDCard) {
        super(renderer, fileOnSDCard);

        mNeedToRenameMtl = false;
    }

    public LoaderOBJ(Renderer renderer, int resourceId) {
        this(renderer.getContext().getResources(), renderer.getTextureManager(), resourceId);
    }

    public LoaderOBJ(Resources resources, TextureManager textureManager, int resourceId) {
        super(resources, textureManager, resourceId);
    }

    public LoaderOBJ(Renderer renderer, File file) {
        super(renderer, file);
    }

    /**
     * 解析数据
     *
     * @return
     * @throws ParsingException
     */
    @Override
    public LoaderOBJ parse() throws ParsingException {
        super.parse();
        // 从raw加载资源
        BufferedReader buffer = null;
        if (mFile == null) {
            // 从raw加载资源
            InputStream fileIn = mResources.openRawResource(mResourceId);
            buffer = new BufferedReader(new InputStreamReader(fileIn));
        } else {
            try {
                buffer = new BufferedReader(new FileReader(mFile));
            } catch (FileNotFoundException e) {
                RajLog.e("[" + getClass().getCanonicalName() + "] Could not find file.");
                e.printStackTrace();
            }
        }
        // 每一行数据
        String line;

        // Ojb索引数据
        ObjIndexData currObjIndexData = new ObjIndexData(new Object3D(generateObjectName()));
        // 索引对象列表
        ArrayList<ObjIndexData> objIndices = new ArrayList<ObjIndexData>();

        // 顶点数据
        ArrayList<Float> vertices = new ArrayList<Float>();
        // 纹理数据
        ArrayList<Float> texCoords = new ArrayList<Float>();
        // 法向量数据
        ArrayList<Float> normals = new ArrayList<Float>();

        // 解析材质用
        MaterialLib matLib = new MaterialLib();
        // 当前材质名称
        String currentMaterialName = null;
        // 是否有面数据的标识
        boolean currentObjHasFaces = false;
        // 组对象
        Object3D currentGroup = mRootObject;
        mRootObject.setName("default");
        Map<String, Object3D> groups = new HashMap<String, Object3D>();

        try {
            // 循环读取每一行的数据
            while ((line = buffer.readLine()) != null) {
                // 忽略 空行和注释
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                // 以空格分割String
                StringTokenizer parts = new StringTokenizer(line, " ");
                int numTokens = parts.countTokens();
                if (numTokens == 0) {
                    continue;
                }
                //
                String type = parts.nextToken();
                // "v" 顶点属性 添加到顶点数组
                if (type.equals(VERTEX)) {
                    vertices.add(Float.parseFloat(parts.nextToken()));
                    vertices.add(Float.parseFloat(parts.nextToken()));
                    vertices.add(Float.parseFloat(parts.nextToken()));
                }
                // "f"面属性  索引数组
                else if (type.equals(FACE)) {
                    // 当前obj对象有面数据
                    currentObjHasFaces = true;
                    // 是否为矩形(android 均为三角形，这里暂时先忽略多边形的情况)
                    boolean isQuad = numTokens == 5;
                    int[] quadvids = new int[4];
                    int[] quadtids = new int[4];
                    int[] quadnids = new int[4];

                    // 如果含有"//" 替换
                    boolean emptyVt = line.indexOf("//") > -1;
                    if (emptyVt) {
                        line = line.replace("//", "/");
                    }
                    // "f 103/1/1 104/2/1 113/3/1"以" "分割
                    parts = new StringTokenizer(line);
                    // “f”
                    parts.nextToken();
                    // "103/1/1 104/2/1 113/3/1"再以"/"分割
                    StringTokenizer subParts = new StringTokenizer(parts.nextToken(), "/");
                    int partLength = subParts.countTokens();

                    // 纹理数据
                    boolean hasuv = partLength >= 2 && !emptyVt;
                    // 法向量数据
                    boolean hasn = partLength == 3 || (partLength == 2 && emptyVt);
                    // 索引index
                    int idx;
                    for (int i = 1; i < numTokens; i++) {
                        if (i > 1) {
                            subParts = new StringTokenizer(parts.nextToken(), "/");
                        }
                        // 顶点索引
                        idx = Integer.parseInt(subParts.nextToken());
                        if (idx < 0) {
                            idx = (vertices.size() / 3) + idx;
                        } else {
                            idx -= 1;
                        }
                        if (!isQuad) {
                            currObjIndexData.vertexIndices.add(idx);
                        } else {
                            quadvids[i - 1] = idx;
                        }
                        // 纹理索引
                        if (hasuv) {
                            idx = Integer.parseInt(subParts.nextToken());
                            if (idx < 0) {
                                idx = (texCoords.size() / 2) + idx;
                            } else {
                                idx -= 1;
                            }
                            if (!isQuad) {
                                currObjIndexData.texCoordIndices.add(idx);
                            } else {
                                quadtids[i - 1] = idx;
                            }
                        }
                        // 法向量数据
                        if (hasn) {
                            idx = Integer.parseInt(subParts.nextToken());
                            if (idx < 0) {
                                idx = (normals.size() / 3) + idx;
                            } else {
                                idx -= 1;
                            }
                            if (!isQuad) {
                                currObjIndexData.normalIndices.add(idx);
                            } else {
                                quadnids[i - 1] = idx;
                            }
                        }
                    }
                    // 如果是多边形
                    if (isQuad) {
                        int[] indices = new int[]{0, 1, 2, 0, 2, 3};
                        for (int i = 0; i < 6; ++i) {
                            int index = indices[i];
                            currObjIndexData.vertexIndices.add(quadvids[index]);
                            currObjIndexData.texCoordIndices.add(quadtids[index]);
                            currObjIndexData.normalIndices.add(quadnids[index]);
                        }
                    }
                }
                // 纹理
                else if (type.equals(TEXCOORD)) {
                    // 这里纹理的Y值，需要(Y = 1-Y0)
                    texCoords.add(Float.parseFloat(parts.nextToken()));
                    texCoords.add(1f - Float.parseFloat(parts.nextToken()));
                }
                // 法向量
                else if (type.equals(NORMAL)) {
                    normals.add(Float.parseFloat(parts.nextToken()));
                    normals.add(Float.parseFloat(parts.nextToken()));
                    normals.add(Float.parseFloat(parts.nextToken()));
                }
                // 组
                else if (type.equals(GROUP)) {
                    int numGroups = parts.countTokens();
                    Object3D previousGroup = null;
                    for (int i = 0; i < numGroups; i++) {
                        String groupName = parts.nextToken();
                        // 创建组
                        if (!groups.containsKey(groupName)) {
                            groups.put(groupName, new Object3D(groupName));
                        }
                        // 获取组
                        Object3D group = groups.get(groupName);
                        if (previousGroup != null) {
                            addChildSetParent(group, previousGroup);
                        } else {
                            currentGroup = group;
                        }
                        previousGroup = group;
                    }
                    RajLog.i("Parsing group: " + currentGroup.getName());
                    if (currentObjHasFaces) {
                        objIndices.add(currObjIndexData);
                        currObjIndexData = new ObjIndexData(new Object3D(generateObjectName()));
                        RajLog.i("Parsing object: " + currObjIndexData.targetObj.getName());
                        currObjIndexData.materialName = currentMaterialName;
                        currentObjHasFaces = false;
                    }
                    addChildSetParent(currentGroup, currObjIndexData.targetObj);
                }
                // 对象
                else if (type.equals(OBJECT)) {
                    // 对象名称
                    String objName = parts.hasMoreTokens() ? parts.nextToken() : generateObjectName();
                    // 面数据
                    if (currentObjHasFaces) {
                        objIndices.add(currObjIndexData);
                        // 创建新的索引对象
                        currObjIndexData = new ObjIndexData(new Object3D(currObjIndexData.targetObj.getName()));
                        // 材质名称
                        currObjIndexData.materialName = currentMaterialName;
                        //
                        addChildSetParent(currentGroup, currObjIndexData.targetObj);
                        currentObjHasFaces = false;
                    }
                    currObjIndexData.targetObj.setName(objName);
                }
                // 材质
                else if (type.equals(MATERIAL_LIB)) {
                    if (!parts.hasMoreTokens()) continue;
                    // 需要重命名材质文件
                    String materialLibPath = mNeedToRenameMtl ? parts.nextToken().replace(".", "_") : parts.nextToken();
                    // 加载材质文件
                    RajLog.d("Found Material Lib: " + materialLibPath);
                    if (mFile != null) {
                        matLib.parse(materialLibPath, null, null);
                    } else {
                        matLib.parse(materialLibPath, mResources.getResourceTypeName(mResourceId), mResources.getResourcePackageName(mResourceId));
                    }
                }
                // 使用材质
                else if (type.equals(USE_MATERIAL)) {
                    // 材质名称
                    currentMaterialName = parts.nextToken();
                    if (currentObjHasFaces) {
                        objIndices.add(currObjIndexData);
                        // 创建一个index对象
                        currObjIndexData = new ObjIndexData(new Object3D(generateObjectName()));
                        RajLog.i("Parsing object: " + currObjIndexData.targetObj.getName());
                        addChildSetParent(currentGroup, currObjIndexData.targetObj);
                        currentObjHasFaces = false;
                    }
                    // 材质名称
                    currObjIndexData.materialName = currentMaterialName;
                }
            }
            //
            buffer.close();
            // 存在索引面数据，添加到index列表中
            if (currentObjHasFaces) {
                RajLog.i("Parsing object: " + currObjIndexData.targetObj.getName());
                objIndices.add(currObjIndexData);
            }
        } catch (IOException e) {
            throw new ParsingException(e);
        }

        // 索引对象数量
        int numObjects = objIndices.size();
        // 循环索引对象
        for (int j = 0; j < numObjects; ++j) {
            ObjIndexData oid = objIndices.get(j);

            int i;
            // 顶点数据 初始化
            float[] aVertices = new float[oid.vertexIndices.size() * 3];
            // 顶点纹理数据 初始化
            float[] aTexCoords = new float[oid.texCoordIndices.size() * 2];
            // 顶点法向量数据 初始化
            float[] aNormals = new float[oid.normalIndices.size() * 3];
            // 顶点颜色数据 初始化
            float[] aColors = new float[oid.colorIndices.size() * 4];
            // 要这个数据有啥用????
            int[] aIndices = new int[oid.vertexIndices.size()];
            // 按照索引，重新组织顶点数据
            for (i = 0; i < oid.vertexIndices.size(); ++i) {
                // 顶点索引，三个一组做为一个三角形
                int faceIndex = oid.vertexIndices.get(i) * 3;
                int vertexIndex = i * 3;
                try {
                    // 按照索引，重新组织顶点数据
                    aVertices[vertexIndex] = vertices.get(faceIndex);
                    aVertices[vertexIndex + 1] = vertices.get(faceIndex + 1);
                    aVertices[vertexIndex + 2] = vertices.get(faceIndex + 2);
                    //
                    aIndices[i] = i;
                } catch (ArrayIndexOutOfBoundsException e) {
                    RajLog.d("Obj array index out of bounds: " + vertexIndex + ", " + faceIndex);
                }
            }
            // 按照索引组织 纹理数据
            if (texCoords != null && texCoords.size() > 0) {
                for (i = 0; i < oid.texCoordIndices.size(); ++i) {
                    int texCoordIndex = oid.texCoordIndices.get(i) * 2;
                    int ti = i * 2;
                    aTexCoords[ti] = texCoords.get(texCoordIndex);
                    aTexCoords[ti + 1] = texCoords.get(texCoordIndex + 1);
                }
            }
            // 按照索引组织 顶点颜色数据(oid.colorIndices里边应该没数据)
            for (i = 0; i < oid.colorIndices.size(); ++i) {
                int colorIndex = oid.colorIndices.get(i) * 4;
                int ti = i * 4;
                aTexCoords[ti] = texCoords.get(colorIndex);
                aTexCoords[ti + 1] = texCoords.get(colorIndex + 1);
                aTexCoords[ti + 2] = texCoords.get(colorIndex + 2);
                aTexCoords[ti + 3] = texCoords.get(colorIndex + 3);
            }
            // 按照索引组织 法向量数据
            for (i = 0; i < oid.normalIndices.size(); ++i) {
                int normalIndex = oid.normalIndices.get(i) * 3;
                int ni = i * 3;
                if (normals.size() == 0) {
                    RajLog.e("[" + getClass().getName() + "] There are no normals specified for this model. Please re-export with normals.");
                    throw new ParsingException("[" + getClass().getName() + "] There are no normals specified for this model. Please re-export with normals.");
                }
                aNormals[ni] = normals.get(normalIndex);
                aNormals[ni + 1] = normals.get(normalIndex + 1);
                aNormals[ni + 2] = normals.get(normalIndex + 2);
            }
            // 数据设置到oid.targetObj中
            oid.targetObj.setData(aVertices, aNormals, aTexCoords, aColors, aIndices, false);
            //
            try {
                matLib.setMaterial(oid.targetObj, oid.materialName);
            } catch (TextureException tme) {
                throw new ParsingException(tme);
            }
            if (oid.targetObj.getParent() == null) {
                addChildSetParent(mRootObject, oid.targetObj);
            }
        }
        for (Object3D group : groups.values()) {
            if (group.getParent() == null) {
                addChildSetParent(mRootObject, group);
            }
        }

        if (mRootObject.getNumChildren() == 1 && !mRootObject.getChildAt(0).isContainer()) {
            mRootObject = mRootObject.getChildAt(0);
        }

        for (int i = 0; i < mRootObject.getNumChildren(); i++) {
            mergeGroupsAsObjects(mRootObject.getChildAt(i));
        }
        return this;
    }


    /**
     * Collapse single-object groups. (Some obj exporters use g token for objects)
     *
     * @param object
     */
    private void mergeGroupsAsObjects(Object3D object) {
        if (object.isContainer() && object.getNumChildren() == 1 && object.getChildAt(0).getName().startsWith("Object")) {
            Object3D child = object.getChildAt(0);
            object.removeChild(child);
            child.setName(object.getName());
            addChildSetParent(object.getParent(), child);
            object.getParent().removeChild(object);
            object = child;
        }

        for (int i = 0; i < object.getNumChildren(); i++) {
            mergeGroupsAsObjects(object.getChildAt(i));
        }
    }

    // 随机生成obj对象名
    private static String generateObjectName() {
        return "Object" + (int) (Math.random() * 10000);
    }

    /**
     * Build string representation of object hierarchy
     *
     * @param parent
     * @param sb
     * @param prefix
     */
    private void buildObjectGraph(Object3D parent, StringBuffer sb, String prefix) {
        sb.append(prefix).append("-->").append((parent.isContainer() ? "GROUP " : "") + parent.getName()).append('\n');
        for (int i = 0; i < parent.getNumChildren(); i++) {
            buildObjectGraph(parent.getChildAt(i), sb, prefix + "\t");
        }
    }

    static private Field mParent;

    static {
        try {
            mParent = Object3D.class.getDeclaredField("mParent");
            mParent.setAccessible(true);
        } catch (NoSuchFieldException e) {
            RajLog.e("Reflection error Object3D.mParent");
        }
    }

    /**
     * Add child and set parent reference.
     * WHY DOES OBJECT3D NOT DO THIS?
     *
     * @param parent
     * @param object
     */
    private static void addChildSetParent(Object3D parent, Object3D object) {
        try {
            parent.addChild(object);
            mParent.set(object, parent);
        } catch (Exception e) {
            RajLog.e("Reflection error Object3D.mParent");
        }
    }

    public String toString() {
        if (mRootObject == null) {
            return "Object not parsed";
        } else {
            StringBuffer sb = new StringBuffer();
            buildObjectGraph(mRootObject, sb, "");
            return sb.toString();
        }
    }

    /**
     * obj索引数据
     */
    protected class ObjIndexData {
        public Object3D targetObj;

        // 顶点index数组
        public ArrayList<Integer> vertexIndices;
        // 纹理index数组
        public ArrayList<Integer> texCoordIndices;
        // 颜色index数组
        public ArrayList<Integer> colorIndices;
        // 法向量index数组
        public ArrayList<Integer> normalIndices;

        // 材质名称
        public String materialName;

        public ObjIndexData(Object3D targetObj) {
            this.targetObj = targetObj;
            vertexIndices = new ArrayList<Integer>();
            texCoordIndices = new ArrayList<Integer>();
            colorIndices = new ArrayList<Integer>();
            normalIndices = new ArrayList<Integer>();
        }
    }


    /**
     * 解析材质文件
     */
    protected class MaterialLib {
        private final String MATERIAL_NAME = "newmtl";
        private final String AMBIENT_COLOR = "Ka";
        private final String DIFFUSE_COLOR = "Kd";
        private final String SPECULAR_COLOR = "Ks";
        private final String SPECULAR_COEFFICIENT = "Ns";
        private final String ALPHA_1 = "d";
        private final String ALPHA_2 = "Tr";
        private final String AMBIENT_TEXTURE = "map_Ka";
        private final String DIFFUSE_TEXTURE = "map_Kd";
        private final String SPECULAR_COLOR_TEXTURE = "map_Ks";
        private final String SPECULAR_HIGHLIGHT_TEXTURE = "map_Ns";
        private final String ALPHA_TEXTURE_1 = "map_d";
        private final String ALPHA_TEXTURE_2 = "map_Tr";
        private final String BUMP_TEXTURE = "map_Bump";

        // 材质类对象数组
        private Stack<MaterialDef> mMaterials;
        private String mResourcePackage;

        public MaterialLib() {
            mMaterials = new Stack<LoaderOBJ.MaterialDef>();
        }

        /**
         * 加载材质文件
         *
         * @param materialLibPath
         * @param resourceType
         * @param resourcePackage
         */
        public void parse(String materialLibPath, String resourceType, String resourcePackage) {
            BufferedReader buffer = null;
            if (mFile == null) {
                mResourcePackage = resourcePackage;
                int identifier = mResources.getIdentifier(materialLibPath, resourceType, resourcePackage);
                try {
                    // 从raw中加载材质文件
                    InputStream fileIn = mResources.openRawResource(identifier);
                    buffer = new BufferedReader(new InputStreamReader(fileIn));
                } catch (Exception e) {
                    RajLog.e("[" + getClass().getCanonicalName() + "] Could not find material library file (.mtl).");
                    return;
                }
            } else {
                try {
                    // 如果mFile不为空，则在其对应路径下寻找材质文件
                    File materialFile = new File(mFile.getParent() + File.separatorChar + materialLibPath);
                    buffer = new BufferedReader(new FileReader(materialFile));
                } catch (Exception e) {
                    RajLog.e("[" + getClass().getCanonicalName() + "] Could not find file.");
                    e.printStackTrace();
                    return;
                }
            }
            // 行数据
            String line;
            //
            MaterialDef matDef = null;
            try {
                while ((line = buffer.readLine()) != null) {
                    // Skip comments and empty lines.
                    if (line.length() == 0 || line.charAt(0) == '#') {
                        continue;
                    }
                    //
                    StringTokenizer parts = new StringTokenizer(line, " ");
                    int numTokens = parts.countTokens();
                    if (numTokens == 0) {
                        continue;
                    }
                    //
                    String type = parts.nextToken();
                    type = type.replaceAll("\\t", "");
                    type = type.replaceAll(" ", "");

                    // 定义一个名为 'xxx'的材质
                    if (type.equals(MATERIAL_NAME)) {
                        if (matDef != null) {
                            mMaterials.add(matDef);
                        }
                        // 创建材质对象
                        matDef = new MaterialDef();
                        // 材质对象名称
                        matDef.name = parts.hasMoreTokens() ? parts.nextToken() : "";
                        RajLog.d("Parsing material: " + matDef.name);
                    }
                    // 散射光
                    else if (type.equals(DIFFUSE_COLOR)) {
                        matDef.diffuseColor = getColorFromParts(parts);
                    }
                    // 环境光
                    else if (type.equals(AMBIENT_COLOR)) {
                        matDef.ambientColor = getColorFromParts(parts);
                    }
                    // 镜面光
                    else if (type.equals(SPECULAR_COLOR)) {
                        matDef.specularColor = getColorFromParts(parts);
                    }
                    // 高光调整参数
                    else if (type.equals(SPECULAR_COEFFICIENT)) {
                        matDef.specularCoefficient = Float.parseFloat(parts.nextToken());
                    }
                    // 溶解度，为0时完全透明，1完全不透明
                    else if (type.equals(ALPHA_1) || type.equals(ALPHA_2)) {
                        matDef.alpha = Float.parseFloat(parts.nextToken());
                    }
                    // 纹理贴图
                    else if (type.equals(AMBIENT_TEXTURE)) {
                        matDef.ambientTexture = parts.nextToken();
                    }
                    // map_Ka，map_Kd，map_Ks：材质的环境（ambient），散射（diffuse）和镜面（specular）贴图
                    else if (type.equals(DIFFUSE_TEXTURE)) {
                        matDef.diffuseTexture = parts.nextToken();
                    } else if (type.equals(SPECULAR_COLOR_TEXTURE)) {
                        matDef.specularColorTexture = parts.nextToken();
                    } else if (type.equals(SPECULAR_HIGHLIGHT_TEXTURE)) {
                        matDef.specularHighlightTexture = parts.nextToken();
                    } else if (type.equals(ALPHA_TEXTURE_1) || type.equals(ALPHA_TEXTURE_2)) {
                        matDef.alphaTexture = parts.nextToken();
                    } else if (type.equals(BUMP_TEXTURE)) {
                        matDef.bumpTexture = parts.nextToken();
                    }
                }
                if (matDef != null) {
                    mMaterials.add(matDef);
                }
                buffer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * @param object       obj数据对象
         * @param materialName 材质名称
         * @throws TextureException
         */
        public void setMaterial(Object3D object, String materialName) throws TextureException {
            // 材质名称为null
            if (materialName == null) {
                RajLog.i(object.getName() + " has no material definition.");
                return;
            }
            // 根据名称查找材质对象
            MaterialDef matDef = null;
            for (int i = 0; i < mMaterials.size(); ++i) {
                if (mMaterials.get(i).name.equals(materialName)) {
                    matDef = mMaterials.get(i);
                    break;
                }
            }
            // 有漫反射纹理对象
            boolean hasTexture = matDef != null && matDef.diffuseTexture != null;
            boolean hasBump = matDef != null && matDef.bumpTexture != null;
            boolean hasSpecularTexture = matDef != null && matDef.specularColorTexture != null;
            boolean hasSpecular = matDef != null && matDef.specularColor > 0xff000000 && matDef.specularCoefficient > 0;

            // 创建材质对象
            Material mat = new Material();
            // 开启光照
            mat.enableLighting(true);
            // 这是啥???
            mat.setDiffuseMethod(new DiffuseMethod.Lambert());
            // 以漫反射材质的颜色做为其显示颜色
            if (matDef != null) {
                int alpha = (int) (matDef.alpha * 255f);
                mat.setColor(((alpha << 24) & 0xFF000000) | (matDef.diffuseColor & 0x00FFFFFF));
            } else {
                mat.setColor((int) (Math.random() * 0xffffff));
            }
            //
            if (hasSpecular || hasSpecularTexture) {
                SpecularMethod.Phong method = new SpecularMethod.Phong();
                method.setSpecularColor(matDef.specularColor);
                method.setShininess(matDef.specularCoefficient);
            }
            // 有慢反射纹理
            if (hasTexture) {
                if (mFile == null) {
                    final String fileNameWithoutExtension = getFileNameWithoutExtension(matDef.diffuseTexture);
                    int id = mResources.getIdentifier(fileNameWithoutExtension, "drawable", mResourcePackage);
                    int etc1Id = mResources.getIdentifier(fileNameWithoutExtension, "raw", mResourcePackage);
                    if (etc1Id != 0) {
                        mat.addTexture(new Texture(object.getName() + fileNameWithoutExtension, new Etc1Texture(object.getName() + etc1Id, etc1Id, id != 0 ? BitmapFactory.decodeResource(mResources, id) : null)));
                    } else if (id != 0) {
                        mat.addTexture(new Texture(object.getName() + fileNameWithoutExtension, id));
                    }
                } else {
                    String filePath = mFile.getParent() + File.separatorChar + getOnlyFileName(matDef.diffuseTexture);
                    if (filePath.endsWith(".pkm")) {
                        FileInputStream fis = null;
                        try {
                            fis = new FileInputStream(filePath);
                            mat.addTexture(new Texture(getFileNameWithoutExtension(matDef.diffuseTexture),
                                    new Etc1Texture(getFileNameWithoutExtension(matDef.diffuseTexture) + "etc1", fis, null)));
                        } catch (FileNotFoundException e) {
                            RajLog.e("File decode error");
                        } finally {
                            try {
                                fis.close();
                            } catch (IOException e) {
                            }
                        }
                    } else {
                        mat.addTexture(new Texture(getFileNameWithoutExtension(matDef.diffuseTexture), BitmapFactory.decodeFile(filePath)));
                    }
                }
                mat.setColorInfluence(0);
            }
            if (hasBump) {
                if (mFile == null) {
                    int identifier = mResources.getIdentifier(getFileNameWithoutExtension(matDef.bumpTexture), "drawable", mResourcePackage);
                    mat.addTexture(new NormalMapTexture(object.getName() + identifier, identifier));
                } else {
                    String filePath = mFile.getParent() + File.separatorChar + getOnlyFileName(matDef.bumpTexture);
                    mat.addTexture(new NormalMapTexture(getOnlyFileName(matDef.bumpTexture), BitmapFactory.decodeFile(filePath)));
                }
            }
            if (hasSpecularTexture) {
                if (mFile == null) {
                    int identifier = mResources.getIdentifier(getFileNameWithoutExtension(matDef.specularColorTexture), "drawable", mResourcePackage);
                    mat.addTexture(new SpecularMapTexture(object.getName() + identifier, identifier));
                } else {
                    String filePath = mFile.getParent() + File.separatorChar + getOnlyFileName(matDef.specularColorTexture);
                    mat.addTexture(new SpecularMapTexture(getOnlyFileName(matDef.specularColorTexture), BitmapFactory.decodeFile(filePath)));
                }
            }
            object.setMaterial(mat);
            if (matDef != null && matDef.alpha < 1f)
                object.setTransparent(true);
        }

        private int getColorFromParts(StringTokenizer parts) {
            int r = (int) (Float.parseFloat(parts.nextToken()) * 255f);
            int g = (int) (Float.parseFloat(parts.nextToken()) * 255f);
            int b = (int) (Float.parseFloat(parts.nextToken()) * 255f);
            return Color.rgb(r, g, b);
        }
    }
}
