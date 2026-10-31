package com.ai.limbs.plugins.artstudio

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Source order follows Krita 6.0.4; one inventory serves UI and capability discovery. */
internal object ArtStudioMenuCatalog {
    const val VERSION = "0.3.0"
    private val definitions = JSONArray(listOf(
        """{
  "id": "Layer",
  "title": "图层",
  "children": [
    {
      "id": "cut_layer_clipboard",
      "title": "剪切图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "copy_layer_clipboard",
      "title": "复制图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "paste_layer_from_clipboard",
      "title": "粘贴图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "separator": true
    },
    {
      "id": "LayerNew",
      "title": "新建",
      "children": [
        {
          "id": "add_new_paint_layer",
          "title": "绘画图层…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "name",
              "type": "string",
              "description": "名称",
              "default": "绘画图层"
            }
          ],
          "documentWrite": true
        },
        {
          "id": "add_new_group_layer",
          "title": "图层组…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "name",
              "type": "string",
              "description": "名称",
              "default": "图层组"
            }
          ],
          "documentWrite": true
        },
        {
          "id": "add_new_clone_layer",
          "title": "克隆图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺实时克隆引用与阵列模型"
        },
        {
          "id": "add_new_shape_layer",
          "title": "矢量图层（基础形状）…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"name","type":"string","description":"名称","default":"矢量图层"}],
          "documentWrite": true
        },
        {
          "id": "add_new_adjustment_layer",
          "title": "滤镜图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "add_new_fill_layer",
          "title": "填充图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "add_new_file_layer",
          "title": "文件图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "separator": true
        },
        {
          "id": "add_new_transparency_mask",
          "title": "透明度蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "add_new_filter_mask",
          "title": "滤镜蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "add_new_colorize_mask",
          "title": "上色蒙版",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "add_new_transform_mask",
          "title": "变形蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "add_new_selection_mask",
          "title": "局部选区蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "separator": true
        },
        {
          "id": "new_from_visible",
          "title": "从可见图层新建",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "duplicatelayer",
          "title": "复制图层或蒙版",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "separator": true
        },
        {
          "id": "cut_selection_to_new_layer",
          "title": "剪切选区到新图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "copy_selection_to_new_layer",
          "title": "复制选区到新图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        }
      ]
    },
    {
      "id": "LayerImportExport",
      "title": "导入/导出",
      "children": [
        {
          "id": "save_node_as_image",
          "title": "保存图层/蒙版为图像…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "name",
              "type": "string",
              "description": "文件名",
              "default": "layer"
            }
          ],
          "documentWrite": false
        },
        {
          "id": "save_vector_node_to_svg",
          "title": "保存矢量图层为 SVG…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
        },
        {
          "id": "save_groups_as_images",
          "title": "保存图层组为图像…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": false
        },
        {
          "separator": true
        },
        {
          "id": "import_layer_from_file",
          "title": "导入图层…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "base64",
              "type": "string",
              "description": "PNG/JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF 内容，按内容识别；动图只导入首帧并返回 warnings；手机页面使用文件选择器。超预算返回 imagePlan；用户同意缩小后在 parameters.confirmResize 传回 imagePlan.confirmation"
            }
          ],
          "documentWrite": true
        },
        {
          "id": "LayerImportAs",
          "title": "导入",
          "children": [
            {
              "id": "import_layer_as_paint_layer",
              "title": "导入为绘画图层…",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [
                {
                  "name": "base64",
                  "type": "string",
                  "description": "PNG/JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF 内容，按内容识别；动图只导入首帧并返回 warnings；手机页面使用文件选择器。超预算返回 imagePlan；用户同意缩小后在 parameters.confirmResize 传回 imagePlan.confirmation"
                }
              ],
              "documentWrite": true
            },
            {
              "id": "import_layer_as_transparency_mask",
              "title": "导入为透明度蒙版",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            },
            {
              "id": "import_layer_as_filter_mask",
              "title": "导入为滤镜蒙版",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            },
            {
              "id": "import_layer_as_selection_mask",
              "title": "导入为选区蒙版",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            }
          ]
        }
      ]
    },
    {
      "id": "LayerConvert",
      "title": "转换",
      "children": [
        {
          "id": "convert_to_paint_layer",
          "title": "转换为绘画图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "convert_to_transparency_mask",
          "title": "转换为透明度蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "convert_to_filter_mask",
          "title": "转换为滤镜蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "convert_to_selection_mask",
          "title": "转换为选区蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "convert_to_file_layer",
          "title": "转换为文件图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "convert_group_to_animated",
          "title": "转换为动画图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "layercolorspaceconversion",
          "title": "转换图层色彩空间…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        }
      ]
    },
    {
      "separator": true
    },
    {
      "id": "LayerSelect",
      "title": "选择图层",
      "children": [
        {
          "id": "select_all_layers",
          "title": "选择所有图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺图层多选模型"
        },
        {
          "id": "select_visible_layers",
          "title": "选择可见图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺图层多选模型"
        },
        {
          "id": "select_invisible_layers",
          "title": "选择不可见图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺图层多选模型"
        },
        {
          "id": "select_locked_layers",
          "title": "选择锁定图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺图层多选模型"
        },
        {
          "id": "select_unlocked_layers",
          "title": "选择未锁定图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺图层多选模型"
        }
      ]
    },
    {
      "id": "LayerGroup",
      "title": "分组",
      "children": [
        {
          "id": "create_quick_group",
          "title": "快速分组…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "name",
              "type": "string",
              "description": "名称",
              "default": "图层组"
            }
          ],
          "documentWrite": true
        },
        {
          "id": "create_quick_clipping_group",
          "title": "快速剪贴组",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "quick_ungroup",
          "title": "取消分组",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        }
      ]
    },
    {
      "id": "LayerTransform",
      "title": "变换",
      "children": [
        {
          "id": "layersize",
          "title": "缩放图层大小…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺统一图层内容边界、非均匀变换或原子多图层变换"
        },
        {
          "id": "mirrorNodeX",
          "title": "水平翻转图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "mirrorNodeY",
          "title": "垂直翻转图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "Rotate",
          "title": "旋转",
          "children": [
            {
              "id": "rotatelayer",
              "title": "旋转图层…",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [
                {
                  "name": "degrees",
                  "type": "number",
                  "description": "旋转角度（顺时针为正）",
                  "default": 15
                }
              ],
              "documentWrite": true
            },
            {
              "separator": true
            },
            {
              "id": "rotateLayerCW90",
              "title": "顺时针旋转 90°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            },
            {
              "id": "rotateLayerCCW90",
              "title": "逆时针旋转 90°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            },
            {
              "id": "rotateLayer180",
              "title": "旋转 180°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            }
          ]
        },
        {
          "id": "shearlayer",
          "title": "斜切图层…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺统一图层内容边界、非均匀变换或原子多图层变换"
        },
        {
          "id": "offsetlayer",
          "title": "偏移图层…",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [
            {
              "name": "dx",
              "type": "number",
              "description": "水平偏移（像素）",
              "default": 0
            },
            {
              "name": "dy",
              "type": "number",
              "description": "垂直偏移（像素）",
              "default": 0
            }
          ],
          "documentWrite": true
        }
      ]
    },
    {
      "id": "LayerTransformAll",
      "title": "变换所有图层",
      "children": [
        {
          "id": "mirrorAllNodesX",
          "title": "水平翻转所有图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "mirrorAllNodesY",
          "title": "垂直翻转所有图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "scaleAllLayers",
          "title": "缩放所有图层…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺统一图层内容边界、非均匀变换或原子多图层变换"
        },
        {
          "id": "Rotate",
          "title": "旋转",
          "children": [
            {
              "id": "rotateAllLayers",
              "title": "旋转所有图层…",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [
                {
                  "name": "degrees",
                  "type": "number",
                  "description": "旋转角度（顺时针为正）",
                  "default": 15
                }
              ],
              "documentWrite": true
            },
            {
              "separator": true
            },
            {
              "id": "rotateAllLayersCW90",
              "title": "顺时针旋转所有图层 90°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            },
            {
              "id": "rotateAllLayersCCW90",
              "title": "逆时针旋转所有图层 90°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            },
            {
              "id": "rotateAllLayers180",
              "title": "旋转所有图层 180°",
              "implemented": true,
              "source": "krita/krita5.xmlgui",
              "parameters": [],
              "documentWrite": true
            }
          ]
        },
        {
          "id": "shearAllLayers",
          "title": "斜切所有图层…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺统一图层内容边界、非均匀变换或原子多图层变换"
        }
      ]
    },
    {
      "id": "LayerSplitAlpha",
      "title": "拆分",
      "children": [
        {
          "id": "LayerSplitAlpha",
          "title": "拆分",
          "children": [
            {
              "id": "split_alpha_into_mask",
              "title": "拆分透明度到蒙版",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            },
            {
              "id": "split_alpha_write",
              "title": "拆分透明度后写入",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            },
            {
              "id": "split_alpha_save_merged",
              "title": "保存合并的透明度",
              "implemented": false,
              "source": "krita/krita5.xmlgui",
              "reason": "尚缺像素选区/蒙版模型与对应算法"
            }
          ]
        },
        {
          "id": "layersplit",
          "title": "拆分图层…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "clones_array",
          "title": "克隆阵列…",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺实时克隆引用与阵列模型"
        }
      ]
    },
    {
      "separator": true
    },
    {
      "id": "EditLayerMetaData",
      "title": "编辑图层元数据…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    },
    {
      "id": "histogram",
      "title": "直方图…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "separator": true
    },
    {
      "id": "merge_layer",
      "title": "向下合并图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "flatten_layer",
      "title": "平整图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "merge_all_shape_layers",
      "title": "合并所有矢量图层",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
    },
    {
      "id": "flatten_image",
      "title": "合并所有图层",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "merge_selected_layers",
      "title": "合并选中图层",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺图层多选模型"
    },
    {
      "separator": true
    },
    {
      "id": "layer_style",
      "title": "图层样式…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    }
  ]
}""",
        """{
  "id": "Select",
  "title": "选择",
  "children": [
    {
      "id": "select_all",
      "title": "全选",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "deselect",
      "title": "取消选择",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "reselect",
      "title": "重新选择",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "invert_selection",
      "title": "反选",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "separator": true
    },
    {
      "id": "selectionscale",
      "title": "缩放选区…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "width",
          "type": "number",
          "description": "选区宽度",
          "default": 100
        },
        {
          "name": "height",
          "type": "number",
          "description": "选区高度",
          "default": 100
        }
      ],
      "documentWrite": true
    },
    {
      "id": "edit_selection",
      "title": "编辑选区…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "x",
          "type": "number",
          "description": "左上角 X",
          "default": 0
        },
        {
          "name": "y",
          "type": "number",
          "description": "左上角 Y",
          "default": 0
        },
        {
          "name": "width",
          "type": "number",
          "description": "选区宽度",
          "default": 100
        },
        {
          "name": "height",
          "type": "number",
          "description": "选区高度",
          "default": 100
        }
      ],
      "documentWrite": true
    },
    {
      "id": "convert_to_vector_selection",
      "title": "转换为矢量选区",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
    },
    {
      "id": "convert_to_raster_selection",
      "title": "转换为像素选区",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    },
    {
      "id": "convert_shapes_to_vector_selection",
      "title": "矢量形状转换为选区",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
    },
    {
      "id": "convert_selection_to_shape",
      "title": "选区转换为矢量形状",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
    },
    {
      "separator": true
    },
    {
      "id": "feather",
      "title": "羽化…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "id": "similar",
      "title": "选择相似区域…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "separator": true
    },
    {
      "id": "toggle_display_selection",
      "title": "显示选区",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "enabled",
          "type": "boolean",
          "description": "显示选区",
          "default": true
        }
      ],
      "documentWrite": false
    },
    {
      "id": "show-global-selection-mask",
      "title": "显示全局选区蒙版",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "separator": true
    },
    {
      "id": "colorrange",
      "title": "颜色范围…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "id": "selectopaquemenu",
      "title": "选择不透明区域",
      "children": [
        {
          "id": "selectopaque",
          "title": "替换选区",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "separator": true
        },
        {
          "id": "selectopaque_add",
          "title": "添加到选区",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "selectopaque_subtract",
          "title": "从选区减去",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "selectopaque_intersect",
          "title": "与选区相交",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        }
      ]
    },
    {
      "separator": true
    },
    {
      "id": "featherselection",
      "title": "羽化选区…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "id": "growselection",
      "title": "扩大选区…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "pixels",
          "type": "number",
          "description": "像素数（当前只支持矩形选区）",
          "default": 4
        }
      ],
      "documentWrite": true
    },
    {
      "id": "shrinkselection",
      "title": "缩小选区…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "pixels",
          "type": "number",
          "description": "像素数（当前只支持矩形选区）",
          "default": 4
        }
      ],
      "documentWrite": true
    },
    {
      "id": "borderselection",
      "title": "边界选区…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "id": "smoothselection",
      "title": "平滑选区…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺像素选区/蒙版模型与对应算法"
    },
    {
      "separator": true
    },
    {
      "id": "enable_sap",
      "title": "自动选择参考图像",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    },
    {
      "id": "configure_sap",
      "title": "配置自动选择参考图像…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    }
  ]
}""",
        """{
  "id": "Filter",
  "title": "滤镜",
  "children": [
    {"id":"filter_apply_again","title":"再次应用上次滤镜","implemented":true,"source":"krita/krita5.xmlgui","parameters":[],"documentWrite":true},
    {"id":"filter_apply_reprompt","title":"再次应用上次滤镜（重新配置）","implemented":true,"source":"krita/krita5.xmlgui","parameters":[],"documentWrite":true},
    {"id":"filter_gallery","title":"滤镜库…","implemented":false,"source":"krita/krita5.xmlgui","reason":"尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"},
    {"separator":true},
    {
      "id": "adjust_filters",
      "title": "调整",
      "children": [
        {"id":"filter.hsv_adjustment","title":"HSV 调整…","implemented":true,"source":"plugins/filters/colorsfilters/kis_hsv_adjustment_filter.cpp","parameters":[{"name":"hue","type":"number","description":"色相偏移角度−360–360；着色模式为绝对色相","default":0},{"name":"saturation","type":"number","description":"饱和度调整−1–1；着色模式为0–1绝对值","default":0},{"name":"value","type":"number","description":"HSV明度或HSL亮度调整−1–1","default":0},{"name":"model","type":"string","description":"颜色模型","default":"hsv","choices":["hsv","hsl"]},{"name":"colorize","type":"boolean","description":"着色模式","default":false},{"name":"clipInput","type":"boolean","description":"明确允许将工作域RGB裁到0–1","default":false},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"支持HSV/HSL；不包含Krita的HSI、HSY、YUV和旧兼容模式。非中性调整要求工作域RGB在0–1内；越界时明确拒绝，或由clipInput显式裁切。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.dodge","title":"减淡…","implemented":true,"source":"plugins/filters/dodgeburn/DodgeBurn.cpp","parameters":[{"name":"range","type":"string","description":"作用范围","default":"midtones","choices":["shadows","midtones","highlights"]},{"name":"exposure","type":"number","description":"曝光量0–1","default":0.5},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"按Krita的阴影／中间调／高光公式处理；中间调幂运算要求工作域RGB非负。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.burn","title":"加深…","implemented":true,"source":"plugins/filters/dodgeburn/DodgeBurn.cpp","parameters":[{"name":"range","type":"string","description":"作用范围","default":"midtones","choices":["shadows","midtones","highlights"]},{"name":"exposure","type":"number","description":"曝光量0–1","default":0.5},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"按Krita的阴影／中间调／高光公式处理；中间调幂运算要求工作域RGB非负，阴影公式裁限负输出。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.desaturate","title":"去色…","implemented":true,"source":"plugins/filters/colorsfilters/kis_desaturate_filter.cpp","parameters":[{"name":"mode","type":"string","description":"去色算法","default":"lightness","choices":["lightness","luminosity","luminosity601","average","min","max"]}],"documentWrite":true},
        {"id":"filter.invert","title":"反相","implemented":true,"source":"plugins/filters/example/example.cpp","parameters":[],"documentWrite":true},
        {"id":"filter.slope_offset_power","title":"斜率、偏移、幂 (ASC-CDL)…","implemented":true,"source":"plugins/filters/asccdl/kis_asccdl_filter.cpp","parameters":[{"name":"slope","type":"array","description":"RGB斜率数组，3项−16–16","default":[1,1,1]},{"name":"offset","type":"array","description":"RGB偏移数组，3项−16–16","default":[0,0,0]},{"name":"power","type":"array","description":"RGB幂数组，3项0.01–16","default":[1,1,1]},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"依次执行pow(RGB×slope+offset,power)。负底数与分数次幂组合明确拒绝；不自动裁切HDR。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.auto_contrast","title":"自动对比度…","implemented":true,"source":"plugins/filters/colorsfilters/colorsfilters.cpp","parameters":[{"name":"percentile","type":"number","description":"直方图两端各裁去的百分比0–49","default":0.5},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"以本层全部非透明像素统计4096档D65 Lab明度；拉伸后再以选区混合输出，非选区专属统计。不是Krita的ICC Lab变换；统计明度限制0–100，平坦图保持原值。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.color_balance","title":"色彩平衡…","implemented":true,"source":"plugins/filters/colorsfilters/kis_color_balance_filter.cpp","parameters":[{"name":"shadows","type":"array","description":"阴影RGB偏移数组，3项−1–1","default":[0,0,0]},{"name":"midtones","type":"array","description":"中间调RGB偏移数组，3项−1–1","default":[0,0,0]},{"name":"highlights","type":"array","description":"高光RGB偏移数组，3项−1–1","default":[0,0,0]},{"name":"preserveLuminosity","type":"boolean","description":"保留HSL亮度","default":true},{"name":"clipInput","type":"boolean","description":"明确允许将工作域RGB裁到0–1","default":false},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"使用Krita的分段权重和HSL亮度恢复；实际调整要求0–1工作域并将结果裁到0–1。尚无ICC色彩空间变换。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.levels","title":"色阶…","implemented":true,"source":"plugins/filters/levelfilter/KisLevelsFilter.cpp","parameters":[{"name":"levels","type":"object","description":"色阶对象：master/red/green/blue/alpha各含black、white、gamma、outBlack、outWhite","default":{"master":{"black":0,"white":1,"gamma":1,"outBlack":0,"outWhite":1}}},{"name":"mode","type":"string","description":"处理模式：channels为RGB，lightness为D65 Lab明度","default":"channels","choices":["channels","lightness"]},{"name":"clipInput","type":"boolean","description":"把每级色阶的归一化输入裁到0–1","default":false},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"white必须大于black；gamma为0.01–100，其余色阶值−16–16。不裁切时采用带符号幂延展HDR；alpha始终裁到0–1。明度模式仅接受master/alpha，不是Krita ICC Lab模式。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.cross_channel_adjustment_curves","title":"跨通道调整曲线…","implemented":true,"source":"plugins/filters/colorsfilters/kis_cross_channel_filter.cpp","parameters":[{"name":"rules","type":"array","description":"按顺序执行的规则数组：driver、target、points","default":[{"driver":"red","target":"green","points":[[0,0.5],[1,0.5]]}]},{"name":"interpolation","type":"string","description":"曲线插值：smooth为保形三次Hermite","default":"smooth","choices":["linear","smooth"]},{"name":"clipInput","type":"boolean","description":"明确允许将工作域RGB裁到0–1","default":false},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"最多16条规则；driver=red/green/blue/alpha/hue/saturation/value/luminance，target另可master。points为2–32个[x,y]节点，端点x=0/1，y为−16–16。增量=2×curve(driver)−1，0.5恒定曲线为中性；越界driver取端值。luminance为所选工作域的Rec.709加权值，target luminance等量加RGB；HSV分支要求0–1。规则按给定顺序执行，不冒用Krita的虚拟通道顺序。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.threshold","title":"阈值…","implemented":true,"source":"plugins/filters/threshold/threshold.cpp","parameters":[{"name":"threshold","type":"integer","description":"阈值（0–255）","default":128}],"documentWrite":true},
        {"id":"filter.color_adjustment_curves","title":"颜色调整曲线…","implemented":true,"source":"plugins/filters","parameters":[{"name":"curves","type":"object","description":"曲线对象：master/red/green/blue/alpha -> [[x,y],…]","default":{"master":[[0,0],[1,1]]}},{"name":"interpolation","type":"string","description":"曲线插值：smooth为保形三次Hermite","default":"smooth","choices":["linear","smooth"]},{"name":"outside","type":"string","description":"HDR区间外处理","default":"linear","choices":["linear","constant"]},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"每条曲线2–32节点，x严格递增且含0/1端点，y为−16–16。master先于RGB单通道；alpha独立且输出裁到0–1。图形编辑支持y为0–1，其余可直接编辑JSON。插值不是Krita的样条实现。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"}
      ]
    },
    {
      "id": "artistic_filters",
      "title": "艺术效果",
      "children": [
        {"id":"filter.pixelize","title":"像素化…","implemented":true,"source":"plugins/filters/pixelizefilter/kis_pixelize_filter.cpp","parameters":[{"name":"pixelWidth","type":"integer","description":"块宽1–512像素","default":10},{"name":"pixelHeight","type":"integer","description":"块高1–512像素","default":10},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"块对齐画布原点；完整块先读取再求线性预乘RGBA平均，边缘块按实际像素数求平均。选区只控制最终写入。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.halftone","title":"半色调…","implemented":true,"source":"plugins/filters/halftone/KisHalftoneFilter.cpp","parameters":[{"name":"mode","type":"string","description":"半色调模式","default":"intensity","choices":["intensity","channels","alpha"]},{"name":"pattern","type":"object","description":"图案生成器：kind、size像素、angle角度、offset二元数组","default":{"kind":"dots","size":8,"angle":45,"offset":[0,0]}},{"name":"channelAngles","type":"array","description":"独立RGB通道附加旋转角，各−360–360度","default":[15,45,75]},{"name":"hardness","type":"number","description":"图案边缘硬度0–1","default":1},{"name":"samples","type":"integer","description":"每轴超采样数1–4","default":2},{"name":"invert","type":"boolean","description":"反转驱动值","default":false},{"name":"space","type":"string","description":"强度／RGB通道驱动工作域","default":"srgb","choices":["srgb","linear"]},{"name":"foreground","type":"string","description":"前景色#AARRGGBB","default":"#FF000000"},{"name":"background","type":"string","description":"背景色#AARRGGBB","default":"#FFFFFFFF"}],"documentWrite":true,"notice":"画室图案生成器提供dots圆点、lines条纹、diamonds菱形；size为1–512像素、angle为−360–360度、offset两项为±65536。强度／RGB驱动裁到0–1；intensity按前背景预乘颜色混合再乘源alpha；channels保留源alpha且忽略前背景alpha；alpha模式保留源直通RGB并对透明度筛网化。原alpha=0的像素及发光RGB保留。包含三种模式，但不等同于Krita完整图案生成器注册表、ICC或噪声LUT。"},
        {"id":"filter.oilpaint","title":"油画…","implemented":true,"source":"plugins/filters/oilpaintfilter/kis_oilpaint_filter.cpp","parameters":[{"name":"brushSize","type":"integer","description":"邻域半径1–8像素","default":1},{"name":"smooth","type":"integer","description":"亮度分桶数2–255","default":30},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"统计邻域主导亮度桶，输出桶内直通RGB平均并保留中心alpha。跳过透明邻点，亮度分桶裁到0–1；RGB平均保留HDR。边缘重复取样，计数使用整数，非Krita的8位计数。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.index_colors","title":"索引颜色…","implemented":true,"source":"plugins/filters/indexcolors/indexcolors.cpp","parameters":[{"name":"ramps","type":"array","description":"1–4条色坡，每条4个明亮→浅色→基色→阴影#AARRGGBB或null","default":[["#FFFFFFFF","#FFFFFF00","#FF808080","#FF000000"]]},{"name":"gradientSteps","type":"array","description":"每坡相邻层级间插色数量，3项整数0–8","default":[4,4,4]},{"name":"betweenSteps","type":"integer","description":"相邻色坡同层级插色数量0–8","default":2},{"name":"diagonal","type":"boolean","description":"生成相邻色坡的对角插色","default":false},{"name":"reduceColors","type":"boolean","description":"合并最近生成色，直到达到colorLimit","default":false},{"name":"colorLimit","type":"integer","description":"减色后最多颜色数2–256","default":32},{"name":"weights","type":"array","description":"D65 Lab颜色距离权重，各0–10，至少一项非零","default":[1,1,1]},{"name":"alphaSteps","type":"integer","description":"透明度等级数0–256；0保留，1为二值","default":1}],"documentWrite":true,"notice":"在D65 Lab中生成色坡、坡间与可选对角插色，最邻近映射并可合并最接近颜色；最大512种生成色。颜色距离按L/100、a/255、b/255归一化，颜色alpha不参与色坡。原透明像素保留；量化成透明的新像素保留独立隐藏颜色。不是Krita的ICC Lab、16位舍入或冗余合并规则；颜色值直接写入历史。共享调色板可在手机选入，按每4色一坡。"},
        {"id":"filter.posterize","title":"色调分离…","implemented":true,"source":"plugins/filters/posterize/posterize.cpp","parameters":[{"name":"steps","type":"integer","description":"量化步数（2–128；包含透明度通道）","default":16}],"documentWrite":true},
        {"id":"filter.raindrops","title":"雨滴…","implemented":true,"source":"plugins/filters/raindropsfilter/kis_raindrops_filter.cpp","parameters":[{"name":"dropSize","type":"integer","description":"最大雨滴直径5–128像素","default":80},{"name":"number","type":"integer","description":"最多生成雨滴数0–256","default":80},{"name":"fishEyes","type":"number","description":"指数鱼眼强度1–100","default":30},{"name":"lighting","type":"number","description":"边缘光影强度0–4","default":1},{"name":"blur","type":"boolean","description":"雨滴边缘3×3预乘柔化","default":true},{"name":"seed","type":"integer","description":"固定随机种子，32位有符号整数；同参数可复现","default":0},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"采用指数径向折射、不重叠圆形雨滴与定向边缘光影；每滴最多256次放置尝试，无可用位置则结束，number是上限。固定种子复现；光影和柔化使用画室模型，不是Krita的8位角度分区光影。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"}
      ]
    },
    {
      "id": "blur_filters",
      "title": "模糊",
      "children": [
        {"id":"filter.blur","title":"模糊…","implemented":true,"source":"plugins/filters/blur/kis_blur_filter.cpp","parameters":[{"name":"radiusX","type":"number","description":"横向半径（0–32 px）","default":5},{"name":"radiusY","type":"number","description":"纵向半径（0–32 px）","default":5},{"name":"angle","type":"number","description":"核旋转角（度）","default":0},{"name":"shape","type":"string","description":"核形状","default":"ellipse","choices":["ellipse","rectangle"]},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true},{"name":"alpha","type":"boolean","description":"同时处理透明度","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.motion_blur","title":"运动模糊…","implemented":true,"source":"plugins/filters/blur/kis_motion_blur_filter.cpp","parameters":[{"name":"distance","type":"number","description":"运动距离（0–128 px）","default":10},{"name":"angle","type":"number","description":"运动方向（度）","default":0},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true},{"name":"alpha","type":"boolean","description":"同时处理透明度","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.lens_blur","title":"镜头模糊…","implemented":true,"source":"plugins/filters/blur/kis_lens_blur_filter.cpp","parameters":[{"name":"radius","type":"number","description":"光圈半径（0–32 px）","default":5},{"name":"sides","type":"integer","description":"光圈边数（3–12）","default":5},{"name":"angle","type":"number","description":"光圈旋转角（度）","default":0},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true},{"name":"alpha","type":"boolean","description":"同时处理透明度","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.gaussian_blur","title":"高斯模糊…","implemented":true,"source":"plugins/filters/blur/kis_gaussian_blur_filter.cpp","parameters":[{"name":"sigmaX","type":"number","description":"横向标准差 σ（0–128 px）","default":3},{"name":"sigmaY","type":"number","description":"纵向标准差 σ（0–128 px）","default":3},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true},{"name":"alpha","type":"boolean","description":"同时处理透明度","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"}
      ]
    },
    {
      "id": "color_filters",
      "title": "颜色",
      "children": [
        {"id":"filter.fast_color_overlay","title":"快速颜色叠加…","implemented":true,"source":"plugins/filters/colors/KisFilterFastColorOverlay.cpp","parameters":[{"name":"color","type":"string","description":"叠加色#AARRGGBB","default":"#FFFFFFFF"},{"name":"opacity","type":"number","description":"叠加不透明度0–1","default":1},{"name":"blend","type":"string","description":"叠加混合模式","default":"normal","choices":["normal","multiply","screen","overlay","darken","lighten","add","difference"]},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"srgb","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"仅提供列出的8种混合模式；颜色透明度×opacity控制强度，保留原像素alpha。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.propagate_colors","title":"扩散颜色…","implemented":true,"source":"plugins/filters/propagatecolors/KisPropagateColorsFilter.cpp","parameters":[{"name":"distanceMetric","type":"string","description":"最近源点的距离度量","default":"euclidean","choices":["euclidean","cityBlock","chessboard"]},{"name":"expansionMode","type":"string","description":"扩散距离范围","default":"unbounded","choices":["bounded","unbounded"]},{"name":"expansionAmount","type":"number","description":"有界距离0–8192像素","default":10},{"name":"alphaMode","type":"string","description":"扩散时透明度处理","default":"expand","choices":["expand","preserve"]},{"name":"useHiddenSeeds","type":"boolean","description":"把已有独立隐藏颜色也作为源点","default":false}],"documentWrite":true,"notice":"精确欧氏平方距离变换或4/8邻接多源广搜；欧氏为精确L2而非Krita的256/362近似路径距离，边界可能不同；同距离按稳定扫描规则取源。源为alpha>0的像素，可显式加入隐藏色源。expand把影响区alpha设为1，含半透明源点；保留原alpha=0且RGB非零的发光像素。preserve不改变可见像素，仅填独立隐藏RGB；可用filter.hidden读取，或expand+useHiddenSeeds显现。隐藏直通RGB限±65504，以独立F16颜色面随历史、动画cel和工程归档保存，不混入可见合成。其他像素或几何编辑会清除此层隐藏面，撤销可恢复；普通合成导出不含隐藏面。"},
        {"id":"filter.maximize","title":"最大化通道","implemented":true,"source":"plugins/filters/colors/kis_minmax_filters.cpp","parameters":[],"documentWrite":true},
        {"id":"filter.minimize","title":"最小化通道","implemented":true,"source":"plugins/filters/colors/kis_minmax_filters.cpp","parameters":[],"documentWrite":true},
        {"id":"filter.color_to_alpha","title":"颜色转透明度…","implemented":true,"source":"plugins/filters/colors/kis_color_to_alpha.cpp","parameters":[{"name":"color","type":"string","description":"目标RGB色#AARRGGBB；此滤镜忽略色值中的alpha","default":"#FFFFFFFF"},{"name":"tolerance","type":"number","description":"工作域RGB最大分量色差阈值，大于0且不超过1","default":1},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"linear","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"透明度乘以min(max(abs(RGB−目标色))/tolerance,1)，反合成恢复RGB。默认在线性光下保留叠回目标底色的结果；不是Krita的ICC色差度量。srgb模式在编码域反合成。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.color_transfer","title":"颜色迁移…","implemented":true,"source":"plugins/filters/fastcolortransfer/fastcolortransfer.cpp","parameters":[{"name":"referenceStats","type":"object","description":"固定D65 Lab参考统计；默认中性预设，也可从根图层读取","default":{"model":"lab-d65","mean":[50,0,0],"std":[15,10,10]}},{"name":"amount","type":"number","description":"迁移强度0–1","default":1}],"documentWrite":true,"notice":"referenceStats含model=lab-d65、mean/std三元数组，可含provenance工程号／修订／图层号。均值限±1e12，标准差0–1e12。filter.reference或手机图层选择产生alpha加权非透明像素统计；参考与源忽略完全透明像素。逐Lab轴做均值／标准差迁移，源零方差轴定义为参考均值；保留alpha与原透明位置。参数保存统计值，重放不再读取参考层；不是Krita ICC Lab16模型。"}
      ]
    },
    {"id":"decor_filters","title":"装饰","implemented":false,"source":"krita/krita5.xmlgui","reason":"尚缺对应工程数据或独立功能实现"},
    {
      "id": "edge_filters",
      "title": "边缘检测",
      "children": [
        {"id":"filter.top_edge_detection","title":"上边缘检测…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.edge_detection","title":"边缘检测…","implemented":true,"source":"plugins/filters/edgedetection/kis_edge_detection_filter.cpp","parameters":[{"name":"type","type":"string","description":"导数核类型","default":"sobel","choices":["sobel","prewitt","simple"]},{"name":"spacingX","type":"integer","description":"横向取样间距1–16像素","default":1},{"name":"spacingY","type":"integer","description":"纵向取样间距1–16像素","default":1},{"name":"heightChannel","type":"string","description":"高度来源通道","default":"luminance","choices":["luminance","red","green","blue","alpha"]},{"name":"strength","type":"number","description":"高度导数强度0–1024","default":1},{"name":"output","type":"string","description":"导数输出","default":"magnitude","choices":["magnitude","xGrowth","xFall","yGrowth","yFall","direction"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"稀疏3×3 Sobel／Prewitt或中心差分，spacing为采样间距，非Krita可变尺寸卷积核半径。RGB输出为所选高度通道的灰度边缘；保留alpha，零导数方向输出0。不是逐RGB通道和透明度边缘模式。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.height_to_normal_map","title":"高度转法线贴图…","implemented":true,"source":"plugins/filters/convertheightnormalmap/kis_convert_height_to_normal_map_filter.cpp","parameters":[{"name":"type","type":"string","description":"导数核类型","default":"sobel","choices":["sobel","prewitt","simple"]},{"name":"spacingX","type":"integer","description":"横向取样间距1–16像素","default":1},{"name":"spacingY","type":"integer","description":"纵向取样间距1–16像素","default":1},{"name":"heightChannel","type":"string","description":"高度来源通道","default":"luminance","choices":["luminance","red","green","blue","alpha"]},{"name":"strength","type":"number","description":"高度导数强度0–1024","default":1},{"name":"swizzle","type":"array","description":"输出RGB轴与符号：x+/x-/y+/y-/z+/z-，恰好3项","default":["x+","y+","z+"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"以归一化向量(-dx×strength,-dy×strength,1)生成线性RGB法线数据，保留alpha；边缘重复取样。spacing为稀疏采样间距，不是Krita可变核半径。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.gaussian_high_pass","title":"高斯高通…","implemented":true,"source":"plugins/filters/gaussianhighpass/gaussianhighpass_filter.cpp","parameters":[{"name":"sigma","type":"number","description":"模糊标准差 σ（0–128 px）","default":3},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"}
      ]
    },
    {
      "id": "emboss_filters",
      "title": "浮雕",
      "children": [
        {"id":"filter.emboss_horizontal_vertical","title":"Emboss Horizontal  Vertical…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.emboss_in_all_directions","title":"全方向浮雕…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.emboss_with_variable_depth","title":"可变深度浮雕…","implemented":true,"source":"plugins/filters/embossfilter/kis_emboss_filter.cpp","parameters":[{"name":"depth","type":"number","description":"深度（10–300）","default":30},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.emboss_vertical_only","title":"垂直浮雕…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.emboss_laplacian","title":"拉普拉斯浮雕…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.emboss_horizontal_only","title":"水平浮雕…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"}
      ]
    },
    {
      "id": "enhance_filters",
      "title": "增强",
      "children": [
        {"id":"filter.mean_removal","title":"去除均值…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.unsharp_mask","title":"反锐化蒙版…","implemented":true,"source":"plugins/filters/unsharp/kis_unsharp_filter.cpp","parameters":[{"name":"sigma","type":"number","description":"模糊标准差 σ（0–128 px）","default":3},{"name":"amount","type":"number","description":"锐化强度（0–10）","default":1},{"name":"threshold","type":"number","description":"线性亮度／通道差阈值（0–1）","default":0},{"name":"lightnessOnly","type":"boolean","description":"只增强线性亮度，保持通道差；非 Lab 模式","default":true},{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.wavelet_noise_reducer","title":"小波降噪…","implemented":true,"source":"plugins/filters/imageenhancement/kis_wavelet_noise_reduction.cpp","parameters":[{"name":"threshold","type":"number","description":"线性RGB正交Haar系数阈值0–16","default":0.03},{"name":"thresholdMode","type":"string","description":"系数阈值方式","default":"soft","choices":["soft","hard"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"独立RGB通道正交Haar分解，保留最粗低频并阈值化全部细节；矩形各轴补到2次幂，边缘重复，保留原alpha及透明像素。系数尺度与Krita的2D变换不同，阈值单位不能直接照搬。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.sharpen","title":"锐化…","implemented":true,"source":"plugins/filters/convolutionfilters/convolutionfilters.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红色通道","default":true},{"name":"green","type":"boolean","description":"处理绿色通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝色通道","default":true}],"documentWrite":true,"notice":"在线性浮点 RGB 空间处理，边缘重复取样；保留 HDR，不承诺与其他 ICC 工作空间的 Krita 逐像素一致。预览为当前图层，参数确认后才写入历史。"},
        {"id":"filter.gaussian_noise_reduction","title":"高斯降噪…","implemented":true,"source":"plugins/filters/imageenhancement/kis_simple_noise_reducer.cpp","parameters":[{"name":"sigma","type":"number","description":"高斯标准差0–32像素","default":1},{"name":"threshold","type":"number","description":"线性直通RGB最大分量差阈值0–16","default":0.06},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"先高斯预乘模糊，仅当源RGB与模糊RGB最大分量差大于threshold时替换颜色，保留原alpha；透明中心不变。阈值不是Krita的ICC色差或8位阈值，卷积不是其圆形遮罩核。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"}
      ]
    },
    {
      "id": "map_filters",
      "title": "映射",
      "children": [
        {"id":"filter.phong_bumpmap","title":"Phong 凹凸贴图…","implemented":true,"source":"plugins/filters/phongbumpmap/kis_phong_bumpmap_filter.cpp","parameters":[{"name":"type","type":"string","description":"导数核类型","default":"sobel","choices":["sobel","prewitt","simple"]},{"name":"spacingX","type":"integer","description":"横向取样间距1–16像素","default":1},{"name":"spacingY","type":"integer","description":"纵向取样间距1–16像素","default":1},{"name":"heightChannel","type":"string","description":"高度来源通道","default":"luminance","choices":["luminance","red","green","blue","alpha"]},{"name":"strength","type":"number","description":"高度导数强度0–1024","default":1},{"name":"normalInput","type":"boolean","description":"直接把线性RGB数据作为法线图","default":false},{"name":"material","type":"string","description":"材质颜色","default":"white","choices":["white","source"]},{"name":"ambient","type":"number","description":"环境光0–1","default":0.1},{"name":"diffuse","type":"number","description":"漫反射系数0–1","default":0.7},{"name":"specular","type":"number","description":"镜面反射系数0–1","default":0.2},{"name":"shininess","type":"number","description":"高光指数1–256","default":24},{"name":"lights","type":"array","description":"定向灯数组0–4项：azimuth/elevation角度、color、intensity","default":[{"azimuth":135,"elevation":45,"color":"#FFFFFFFF","intensity":1}]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"标准Phong反射，视线朝+z，灯azimuth为−360–360度、从+x向+y，elevation为−90–90度、朝+z，强度0–16；颜色#AARRGGBB，alpha乘灯强度。法线输入RGB须0–1且向量非零；保留源alpha和HDR光强。与Krita旧高光公式、坐标和16位裁限不同。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.round_corners","title":"圆角…","implemented":true,"source":"plugins/filters/roundcorners/kis_round_corners_filter.cpp","parameters":[{"name":"radius","type":"number","description":"圆角半径0–8192像素，实际不超过短边一半","default":30},{"name":"antialias","type":"boolean","description":"圆角边缘1像素抗锯齿","default":true},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"按画布矩形生成圆角覆盖率，在线性预乘空间同时缩放RGBA；radius=0为恒等。几何使用像素中心，与Krita硬裁边界规则不同。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.small_tiles","title":"小瓷砖…","implemented":true,"source":"plugins/filters/smalltilesfilter/kis_small_tiles_filter.cpp","parameters":[{"name":"numberOfTiles","type":"integer","description":"每轴重复次数1–16；每块至少1×1像素","default":2},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"整幅图按像素面积平均缩成floor(宽/n)×floor(高/n)瓷砖，双轴重复n次。画布末尾余数透明；选区仅混合最终结果，不改变瓷砖来源。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.normalize","title":"归一化…","implemented":true,"source":"plugins/filters/normalize/kis_normalize.cpp","parameters":[{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true}],"documentWrite":true,"notice":"法线贴图归一化：在线性RGB数据上将2×RGB−1作为向量，归一化后映回0–1；零向量输出(0.5,0.5,0.5)。保留alpha；不是亮度拉伸。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.gradient_map","title":"渐变映射…","implemented":true,"source":"plugins/filters/gradientmap/KisGradientMapFilter.cpp","parameters":[{"name":"stops","type":"array","description":"渐变节点数组：position与线性直通rgba；2–16项","default":[{"position":0,"rgba":[0,0,0,1]},{"position":1,"rgba":[1,1,1,1]}]},{"name":"inputBlack","type":"number","description":"输入线性亮度黑点−65504–65504","default":0},{"name":"inputWhite","type":"number","description":"输入线性亮度白点；必须大于黑点","default":1},{"name":"space","type":"string","description":"RGB工作域；linear为线性光，srgb为带符号扩展sRGB","default":"linear","choices":["linear","srgb"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"position严格递增且含0/1端点；rgba的RGB为有限线性HDR值±65504，alpha为0–1。以线性Rec.709亮度取样，在所选工作域预乘插值，渐变alpha乘原alpha。当前使用显式节点与平滑插值，不接资源库、最近色和抖动模式；alpha相乘与Krita取两者最小值的规则不同。 手机、menu.preview与menu.execute共用本实现。 原alpha为0的像素保持原值，含发光RGB；透明度曲线不填充空白。"},
        {"id":"filter.palettize","title":"调色板量化…","implemented":true,"source":"plugins/filters/palettize/palettize.cpp","parameters":[{"name":"palette","type":"array","description":"2–512色#AARRGGBB数组，可采用共享调色板快照","default":["#FF000000","#FFFFFFFF"]},{"name":"distance","type":"string","description":"颜色距离工作域","default":"lab","choices":["lab","srgb","linear"]},{"name":"weights","type":"array","description":"各轴颜色距离权重0–10，至少一项非零","default":[1,1,1]},{"name":"dither","type":"string","description":"颜色抖动方式","default":"none","choices":["none","ordered","nearest"]},{"name":"ditherScale","type":"number","description":"有序抖动偏移幅度0–1","default":0.125},{"name":"alphaMode","type":"string","description":"透明度处理","default":"preserve","choices":["preserve","clip","index","dither","palette"]},{"name":"alphaClip","type":"number","description":"clip透明度阈值0–1","default":0.5},{"name":"alphaIndex","type":"integer","description":"index模式的透明颜色索引，从0开始","default":0}],"documentWrite":true,"notice":"支持D65 Lab、扩展sRGB和线性RGB距离；4×4 Bayer有序偏移或最近两色概率阈值抖动。alpha可保留、裁切、指定透明色索引、Bayer抖动或乘所选调色板色alpha。原alpha=0像素保留；新透明像素颜色写入独立隐藏面。资源以实际颜色数组固定，不随共享调色板更改；不是Krita ICC、图案抖动资源库或最近色树实现。"}
      ]
    },
    {"id":"nonphotorealistic_filters","title":"非真实感","implemented":false,"source":"krita/krita5.xmlgui","reason":"尚缺对应工程数据或独立功能实现"},
    {
      "id": "other_filters",
      "title": "其他",
      "children": [
        {"id":"filter.wave","title":"波浪…","implemented":true,"source":"plugins/filters/wavefilter/wavefilter.cpp","parameters":[{"name":"amplitudeX","type":"number","description":"横向位移振幅−4096–4096像素","default":4},{"name":"amplitudeY","type":"number","description":"纵向位移振幅−4096–4096像素","default":4},{"name":"periodX","type":"number","description":"横向位移沿y轴的完整周期0.01–65536像素","default":50},{"name":"periodY","type":"number","description":"纵向位移沿x轴的完整周期0.01–65536像素","default":50},{"name":"phaseX","type":"number","description":"横向相位角−360–360度","default":0},{"name":"phaseY","type":"number","description":"纵向相位角−360–360度","default":0},{"name":"shapeX","type":"string","description":"横向波形","default":"sine","choices":["sine","triangle"]},{"name":"shapeY","type":"string","description":"纵向波形","default":"sine","choices":["sine","triangle"]},{"name":"border","type":"string","description":"越界取样：repeat边缘重复、wrap环绕、transparent透明","default":"repeat","choices":["repeat","wrap","transparent"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"从不可变输入做线性预乘双线性取样。period为完整周期、phase为角度，与Krita源码的wavelength／shift参数单位不同；三角波为连续的标准三角波。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.resettransparent","title":"重置透明像素","implemented":true,"source":"plugins/filters/resettransparent/KisResetTransparentFilter.cpp","parameters":[],"documentWrite":true},
        {"id":"filter.random_noise","title":"随机噪点…","implemented":true,"source":"plugins/filters/noisefilter/noisefilter.cpp","parameters":[{"name":"level","type":"number","description":"像素选中概率百分比0–100","default":50},{"name":"opacity","type":"number","description":"与原像素混合比例百分比0–100","default":100},{"name":"grayscale","type":"boolean","description":"灰度噪点","default":false},{"name":"seed","type":"integer","description":"固定随机种子，32位有符号整数；同参数可复现","default":0},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"被选像素混合确定性sRGB随机色，颜色转为线性后以预乘RGBA混合；噪点色alpha为1，因此默认可在透明区域生效。种子哈希算法不同于Krita。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"},
        {"id":"filter.random_pick","title":"随机拾取…","implemented":true,"source":"plugins/filters/randompickfilter/randompickfilter.cpp","parameters":[{"name":"level","type":"number","description":"像素选中概率百分比0–100","default":50},{"name":"opacity","type":"number","description":"与原像素混合比例百分比0–100","default":100},{"name":"windowsize","type":"number","description":"随机取样窗口宽0–4096像素","default":2.5},{"name":"seed","type":"integer","description":"固定随机种子，32位有符号整数；同参数可复现","default":0},{"name":"border","type":"string","description":"越界取样：repeat边缘重复、wrap环绕、transparent透明","default":"repeat","choices":["repeat","wrap","transparent"]},{"name":"red","type":"boolean","description":"处理红通道","default":true},{"name":"green","type":"boolean","description":"处理绿通道","default":true},{"name":"blue","type":"boolean","description":"处理蓝通道","default":true},{"name":"alpha","type":"boolean","description":"处理透明度通道","default":true}],"documentWrite":true,"notice":"窗口内随机双线性取样，再以预乘RGBA混合；固定种子和画布坐标决定结果，不读取正在修改的输出。不同于Krita整数位置取样及其随机算法。 手机与filter.catalog发现、menu.preview预览、menu.execute应用共用此实现。"}
      ]
    },
    {"separator":true},
    {"id":"QMic","title":"G’MIC-Qt…","implemented":false,"source":"krita/krita5.xmlgui","reason":"尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"},
    {"id":"QMicAgain","title":"再次应用 G’MIC-Qt","implemented":false,"source":"krita/krita5.xmlgui","reason":"尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"}
  ]
}""",
        """{
  "id": "tools",
  "title": "工具",
  "children": [
    {
      "id": "scripts",
      "title": "脚本",
      "children": [
        {
          "id": "script.scripter",
          "title": "脚本编辑器",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺脚本执行器与脚本权限协议"
        }
      ]
    }
  ]
}""",
        """{
  "id": "settings",
  "title": "设置",
  "children": [
    {
      "id": "art.storage_directory",
      "title": "默认保存目录…",
      "implemented": true,
      "source": "Art Studio",
      "parameters": [
        {
          "name": "directory",
          "type": "string",
          "description": "目录绝对路径，留空恢复插件内默认目录",
          "default": "",
          "allowBlank": true
        }
      ],
      "documentWrite": false
    },
    {
      "id": "options_configure",
      "title": "配置画室…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "brushWidth",
          "type": "number",
          "description": "默认画笔宽度（0.1–512 像素）",
          "default": 6
        },
        {
          "name": "brushOpacity",
          "type": "number",
          "description": "默认画笔不透明度（0–1）",
          "default": 1
        },
        {"name":"confirmPanelClose","type":"boolean","description":"关闭面板前显示确认提示","default":true}
      ],
      "documentWrite": false
    },
    {
      "id": "change_interface_scale",
      "title": "界面缩放…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "id": "manage_bundles",
      "title": "管理资源包…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺资源包/资源管理或付款订阅服务"
    },
    {
      "id": "manage_resources",
      "title": "管理资源…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺资源包/资源管理或付款订阅服务"
    },
    {
      "id": "manage_supporter_bundles",
      "title": "管理支持者资源包…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺资源包/资源管理或付款订阅服务"
    },
    {
      "id": "manage_donations",
      "title": "管理捐赠…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺资源包/资源管理或付款订阅服务"
    },
    {
      "id": "manage_subscriptions",
      "title": "管理订阅…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺资源包/资源管理或付款订阅服务"
    },
    {
      "separator": true
    },
    {
      "id": "options_configure_toolbars",
      "title": "配置工具栏…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "id": "toolbar.list",
      "title": "工具栏",
      "children": [
        {
          "id": "toolbar.file",
          "title": "文件工具栏",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "toolbar.edit",
          "title": "编辑工具栏",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "toolbar.brush",
          "title": "画笔和其他工具",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "toolbar.custom1",
          "title": "自定义工具栏 1",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "toolbar.custom2",
          "title": "自定义工具栏 2",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        }
      ]
    },
    {
      "id": "lock_toolbars",
      "title": "锁定工具栏",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "separator": true
    },
    {
      "id": "view_toggledockers",
      "title": "显示/隐藏停靠面板",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [
        {
          "name": "enabled",
          "type": "boolean",
          "description": "隐藏停靠面板",
          "default": true
        }
      ],
      "documentWrite": false
    },
    {
      "id": "settings_dockers_menu",
      "title": "停靠面板",
      "children": [
        {
          "id": "docker.color",
          "title": "多功能拾色器",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"enabled","type":"boolean","description":"显示此停靠面板","default":true}],
          "documentWrite": false
        },
        {
          "id": "docker.layers",
          "title": "图层",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"enabled","type":"boolean","description":"显示此停靠面板","default":true}],
          "documentWrite": false
        },
        {
          "id": "docker.brushes",
          "title": "笔刷预设（基础面板）",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"enabled","type":"boolean","description":"显示此停靠面板","default":true}],
          "documentWrite": false
        },
        {
          "id": "docker.footprints",
          "title": "足迹",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"enabled","type":"boolean","description":"显示此停靠面板","default":true}],
          "documentWrite": false
        },
        {
          "id": "docker.animation",
          "title": "动画时间轴",
          "implemented": true,
          "source": "krita/krita5.xmlgui",
          "parameters": [{"name":"enabled","type":"boolean","description":"显示此停靠面板","default":true}],
          "documentWrite": false
        },
        {
          "id": "docker.pending.onion",
          "title": "洋葱皮",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.channels",
          "title": "通道",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.histogram",
          "title": "直方图面板",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.navigator",
          "title": "导航器",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.reference",
          "title": "参考图像",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.palette",
          "title": "色板",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.brush_editor",
          "title": "笔刷编辑器",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.tool_options",
          "title": "工具选项面板",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.resources",
          "title": "资源管理",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.compositions",
          "title": "构图",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.overview",
          "title": "概览",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.patterns",
          "title": "图案",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.gamut",
          "title": "色域蒙版",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
        },
        {
          "id": "docker.pending.svg",
          "title": "SVG 符号",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
        }
      ]
    },
    {
      "separator": true
    },
    {
      "id": "theme_menu",
      "title": "主题",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "id": "style_menu",
      "title": "界面样式",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "separator": true
    },
    {
      "separator": true
    },
    {
      "id": "switch_application_language",
      "title": "切换应用语言…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺可配置主题、样式、缩放、工具栏或多语言资源"
    },
    {
      "id": "settings_active_author",
      "title": "当前作者资料…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "协作执行者固定记录 AWEI/LANER，尚缺独立作者资料模型"
    },
    {
      "separator": true
    },
    {
      "id": "reset_configurations",
      "title": "重置画室配置…",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "separator": true
    }
  ]
}""",
        """{
  "id": "window",
  "title": "窗口",
  "children": [
    {
      "id": "view_newwindow",
      "title": "新建窗口",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有一个活动画布，尚缺多窗口运行时"
    },
    {
      "id": "windows_cascade",
      "title": "层叠窗口",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有一个活动画布，尚缺多窗口运行时"
    },
    {
      "id": "windows_tile",
      "title": "平铺窗口",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有一个活动画布，尚缺多窗口运行时"
    },
    {
      "id": "windows_next",
      "title": "下一个窗口",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有一个活动画布，尚缺多窗口运行时"
    },
    {
      "id": "windows_previous",
      "title": "上一个窗口",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有一个活动画布，尚缺多窗口运行时"
    },
    {
      "id": "window.current",
      "title": "当前画布",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    }
  ]
}""",
        """{
  "id": "help",
  "title": "帮助",
  "children": [
    {
      "id": "help_contents",
      "title": "画室手册",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "id": "help_whats_this",
      "title": "这是什么？",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "separator": true
    },
    {
      "id": "help_show_tip",
      "title": "每日提示",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "separator": true
    },
    {
      "id": "help_report_bug",
      "title": "报告问题…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺插件自己的问题提交/日志归档入口"
    },
    {
      "id": "buginfo",
      "title": "用于问题报告的信息",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "id": "sysinfo",
      "title": "系统信息",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "id": "color_management_report",
      "title": "色彩管理报告",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "当前只有 RGB 8 位，尚缺 ICC 色彩管理"
    },
    {
      "id": "logcatdump",
      "title": "Android 日志",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺插件自己的问题提交/日志归档入口"
    },
    {
      "id": "crashlog",
      "title": "崩溃日志",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺插件自己的问题提交/日志归档入口"
    },
    {
      "separator": true
    },
    {
      "id": "help_about_app",
      "title": "关于画室",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": false
    },
    {
      "id": "help_about_kde",
      "title": "关于 KDE",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "画室未接入 KDE 运行时"
    }
  ]
}"""
    ).joinToString(",",prefix="[",postfix="]"))
    fun menus(): JSONArray = JSONArray(definitions.toString())
    fun find(id: String): JSONObject? {
        fun search(a: JSONArray): JSONObject? {
            for (n in 0 until a.length()) {
                val e=a.getJSONObject(n)
                if (e.optString("id")==id && !e.has("children")) return JSONObject(e.toString())
                e.optJSONArray("children")?.let { child -> search(child)?.let { return it } }
            }
            return null
        }
        return search(definitions)
    }
    fun availability(item: JSONObject, context: JSONObject): Pair<Boolean,String> {
        if (!item.optBoolean("implemented")) return false to item.optString("reason", "尚未实现")
        val id=item.getString("id")
        val snap=context.optJSONObject("document")
        val state=snap?.getJSONObject("state")
        if (id=="art.storage_directory" && !context.has("storage")) return false to "正在读取保存目录"
        val independent=id in setOf("art.storage_directory","help_contents","help_whats_this","help_show_tip","buginfo","sysinfo","help_about_app",
            "options_configure","reset_configurations","view_toggledockers") || id.startsWith("docker.")
        if (state==null) return independent to if (independent) "" else "请先打开画室工程"
        val selected=ArtMenuOperations.active(state)
        val hasLayer=selected!=null
        val root=selected?.optString("parentId")==""
        val unlocked=selected?.let { !ArtMenuOperations.isLocked(state,it) }==true
        fun result(ok:Boolean, reason:String)=ok to if(ok) "" else reason
        if (independent || id in setOf("select_all","toggle_display_selection","window.current","new_from_visible")) return true to ""
        return when(id) {
            "paste_layer_from_clipboard" -> result(context.getBoolean("layerClipboard"),"图层剪贴板为空")
            "copy_layer_clipboard","save_node_as_image","histogram" -> result(hasLayer && root,"请选择根图层或根图层组")
            "cut_layer_clipboard" -> result(hasLayer && root && ArtMenuOperations.subtree(state,selected!!.getString("id")).none { ArtMenuOperations.isLocked(state,it) },"剪切需要未锁定的根图层或根图层组")
            "merge_layer" -> if(!hasLayer) false to "请先选择图层" else { try { ArtMenuOperations.mergePair(state); true to "" } catch(e:IllegalArgumentException) { false to (e.message ?: "当前不能合并") } }
            "flatten_layer","convert_to_paint_layer" -> result(hasLayer && root && unlocked && ArtMenuOperations.subtree(state,selected!!.getString("id")).all { it.getBoolean("visible") && !ArtMenuOperations.isLocked(state,it) },"平整需要可见、未锁定的根图层树")
            "flatten_image" -> result(ArtMenuOperations.layers(state).none { ArtMenuOperations.isLocked(state,it) },"请先解锁图层")
            "quick_ungroup" -> result(ArtMenuOperations.canUngroup(state),"取消分组需要可见、未锁定、正常混合且无变换的图层组")
            "create_quick_group" -> result(hasLayer && unlocked,"请选择未锁定图层")
            "rotatelayer","rotateLayerCW90","rotateLayerCCW90","rotateLayer180","offsetlayer" -> result(hasLayer && root && unlocked,"请选择未锁定根图层")
            "mirrorNodeX","mirrorNodeY" -> result(ArtMenuOperations.pixelsEditable(state) && state.optJSONObject("selection")==null,"翻转需要可编辑根像素图层且无活动选区")
            "mirrorAllNodesX","mirrorAllNodesY" -> result(state.optJSONObject("selection")==null && ArtMenuOperations.layers(state).isNotEmpty() && ArtMenuOperations.layers(state).all { it.optString("parentId").isBlank() && it.getString("kind") in setOf("paint","image") && it.getBoolean("visible") && !it.getBoolean("locked") && it.getDouble("x")==0.0 && it.getDouble("y")==0.0 && it.getDouble("scale")==1.0 && !it.has("affine") && it.getDouble("rotation")==0.0 },"整体翻转目前需要全部为可见、未锁定、无变换的根像素层，且无选区")
            "rotateAllLayers","rotateAllLayersCW90","rotateAllLayersCCW90","rotateAllLayers180" -> result(ArtMenuOperations.layers(state).isNotEmpty() && ArtMenuOperations.layers(state).none { ArtMenuOperations.isLocked(state,it) },"请先解锁所有图层")
            "cut_selection_to_new_layer" -> result(state.optJSONObject("selection")!=null && ArtMenuOperations.pixelsEditable(state),"请选择可编辑根像素图层并创建选区")
            "copy_selection_to_new_layer" -> result(state.optJSONObject("selection")!=null && hasLayer && root && selected!!.getString("kind") in setOf("paint","image") && selected.getBoolean("visible"),"请选择可见根像素图层并创建选区")
            "save_groups_as_images" -> result(ArtMenuOperations.layers(state).any { it.getString("kind")=="group" && it.optString("parentId").isBlank() },"尚无根图层组")
            "add_new_colorize_mask" -> result(hasLayer && selected!!.getString("kind") in setOf("paint","image","group") &&
                ArtMove.visibility(state,selected)>0,"请选择可见绘画、图像或线稿组；支持图层及父组变换")
            "duplicatelayer" -> result(hasLayer,"请先选择图层")
            "add_new_paint_layer","add_new_group_layer" -> result(!hasLayer || unlocked,"当前父图层已锁定")
            "deselect","selectionscale","edit_selection" -> result(state.optJSONObject("selection")!=null,"请先创建选区")
            "growselection","shrinkselection" -> result(state.optJSONObject("selection")?.optString("shape","rect")=="rect","扩大/缩小当前只支持矩形选区")
            "reselect" -> result(state.optJSONObject("previousSelection")!=null,"尚无已取消的选区")
            "filter_apply_again","filter_apply_reprompt" -> result(state.optJSONObject("lastFilter")!=null && ArtMenuOperations.pixelsEditable(state),"需要上次滤镜和可编辑的根像素图层")
            else -> if(id.startsWith("filter.")) result(ArtMenuOperations.pixelsEditable(state),"滤镜需要可见、未锁定且未经变换的根像素图层") else true to ""
        }
    }
    fun describe(context: JSONObject): JSONObject {
        val menus=menus()
        fun annotate(a:JSONArray) {
            for(n in 0 until a.length()) {
                val item=a.getJSONObject(n)
                item.optJSONArray("children")?.let { annotate(it) }
                if(item.has("id") && !item.has("children")) {
                    val status=availability(item,context)
                    item.put("enabled",status.first).put("unavailableReason",status.second)
                    if(item.optBoolean("implemented") && (item.getString("id").startsWith("filter.") || item.getString("id") in setOf("filter_apply_again","filter_apply_reprompt"))) item.put("filterPreview",true)
                    context.optJSONObject("dockPanels")?.let { dock ->
                        val panel = item.getString("id").removePrefix("docker.")
                        if (panel in ArtDockPanels.ids)
                            item.put("checkable", true).put("checked", dock.getJSONObject("visible").getBoolean(panel))
                        if (item.getString("id") == "options_configure") {
                            val fields = item.getJSONArray("parameters")
                            for (i in 0 until fields.length()) {
                                val field = fields.getJSONObject(i)
                                if (field.getString("name") == "confirmPanelClose") field.put("default", dock.getBoolean("confirmClose"))
                            }
                        }
                    }
                    if(item.getString("id")=="art.storage_directory" && context.has("storage")) {
                        item.getJSONArray("parameters").getJSONObject(0).put("default",
                            context.getJSONObject("storage").getString("configuredDirectory"))
                    }
                    if(item.getString("id")=="filter_apply_reprompt") {
                        context.optJSONObject("document")?.getJSONObject("state")?.optJSONObject("lastFilter")?.let { previous ->
                            val fields=find(previous.getString("action"))!!.getJSONArray("parameters")
                            val old=previous.getJSONObject("parameters")
                            for(i in 0 until fields.length()) {
                                val field=fields.getJSONObject(i)
                                if(old.has(field.getString("name"))) field.put("default",old.get(field.getString("name")))
                            }
                            item.put("parameters",fields)
                        }
                    }
                }
            }
        }
        annotate(menus)
        val doc=context.optJSONObject("document")
        return JSONObject().put("version",VERSION).put("sourceVersion","Krita 6.0.4")
            .put("menus",menus).put("dockPanels",context.optJSONObject("dockPanels") ?: JSONObject.NULL)
            .put("storage",context.optJSONObject("storage") ?: JSONObject.NULL)
            .put("documentId",doc?.getString("id") ?: JSONObject.NULL)
            .put("revision",doc?.getInt("revision") ?: JSONObject.NULL)
            .put("mutationProtocol","documentWrite=true 的项目必须携带 documentId 与 expectedRevision；灰色项目没有执行入口")
    }
    /** Narrow discovery for Laner: the menu definition remains the only parameter contract. */
    fun filters(context:JSONObject,action:String?=null):JSONObject {
        val menu=describe(context)
        val filters=JSONArray()
        val doc=context.optJSONObject("document")
        fun collect(items:JSONArray,group:String) {
            for(i in 0 until items.length()) {
                val item=items.getJSONObject(i)
                val children=item.optJSONArray("children")
                if(children!=null)collect(children,item.getString("title"))
                else if(item.optString("id").startsWith("filter.") && (action==null || item.getString("id")==action)) {
                    val entry=JSONObject(item.toString()).put("group",group)
                    if(item.getBoolean("implemented")) {
                        val defaults=JSONObject()
                        val specs=item.getJSONArray("parameters")
                        for(j in 0 until specs.length()) {
                            val field=specs.getJSONObject(j)
                            if(field.has("default"))defaults.put(field.getString("name"),field.get("default"))
                        }
                        val args=JSONObject().put("action",item.getString("id")).put("parameters",defaults)
                            .put("documentId",doc?.getString("id") ?: "DOCUMENT_ID")
                            .put("expectedRevision",doc?.getInt("revision") ?: 0)
                        entry.put("calls",JSONObject()
                            .put("preview",JSONObject().put("capabilityId","plugin.art.studio.menu.preview")
                                .put("parameters",JSONObject(args.toString()).put("maxEdge",512)))
                            .put("apply",JSONObject().put("capabilityId","plugin.art.studio.menu.execute")
                                .put("parameters",JSONObject(args.toString()))))
                    }
                    filters.put(entry)
                }
            }
        }
        collect(menu.getJSONArray("menus"),"")
        require(action==null || filters.length()==1) {"未知具体滤镜：$action"}
        return JSONObject().put("filters",filters).put("documentId",menu.get("documentId"))
            .put("revision",menu.get("revision")).put("historyWritten",false)
            .put("resourceCapability","plugin.art.studio.filter.resources").put("referenceCapability","plugin.art.studio.filter.reference")
            .put("hiddenColorCapability","plugin.art.studio.filter.hidden")
            .put("usage","implemented表示本插件有声明参数范围内的实现，并非Krita全部模式或ICC等价；先读notice和参数说明。enabled=false时刷新工程或处理unavailableReason。calls含默认参数模板，可直接替换参数；无工程时DOCUMENT_ID/0仅为占位。预览只读，确认使用相同documentId/expectedRevision执行；成功后重新读取修订。")
    }
    fun help(action:String,context:JSONObject):JSONObject = when(action) {
        "buginfo","sysinfo" -> JSONObject().put("pluginVersion",VERSION).put("androidRelease",Build.VERSION.RELEASE)
            .put("sdk",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("supportedAbis",JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("document",context.optJSONObject("document")?.let { snap -> JSONObject().put("id",snap.getString("id"))
                .put("revision",snap.getInt("revision")).put("width",snap.getJSONObject("state").getInt("width"))
                .put("height",snap.getJSONObject("state").getInt("height")) } ?: JSONObject.NULL)
        "help_about_app" -> JSONObject().put("title","AI Limbs 画室 $VERSION")
            .put("text","阿伟与兰儿共用工程、图层和操作历史。菜单参考 Krita 6.0.4；当前使用画室自己的线性浮点 RGBA 工程与 Android 渲染；菜单滤镜的工作域和限制见 filter.catalog，不含 Krita/Qt 引擎。")
        "help_whats_this" -> JSONObject().put("title","这是什么？").put("text","画布工具记录可撤销操作；右侧足迹可切换历史状态。菜单灰色表示当前不可执行，menu.catalog 返回具体原因。图层、笔刷和拾色器面板共用同一工程。")
        "help_show_tip" -> JSONObject().put("title","画室提示").put("text","协作编辑前先读取工程 revision；兰儿菜单写操作携带 documentId 与 expectedRevision，避免把阿伟的新修改覆盖掉。右侧足迹可恢复合并或滤镜之前的状态。")
        else -> JSONObject().put("title","画室手册").put("text","新建或打开工程后，通过左侧工具作画，右侧管理图层与足迹。文件→保存写入 .ailart；导出写入 PNG/JPEG。图层剪贴板复制完整图层树，与编辑菜单的像素剪贴板分开。基础滤镜作用于当前可编辑根图层并遵循选区。兰儿使用 filter.catalog 查询滤镜及结构化参数，通过 menu.preview 只读预览，再以同一工程修订调用 menu.execute；其他菜单由 menu.catalog 查询。")
    }
}
