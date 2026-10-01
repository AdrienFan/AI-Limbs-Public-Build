package com.ai.limbs.plugins.artstudio

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Source order follows Krita 6.0.4; one inventory serves UI and capability discovery. */
internal object ArtStudioMenuCatalog {
    const val VERSION = "0.2.21"
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
          "title": "矢量图层",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺可编辑矢量对象、路径或 SVG 引擎"
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
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
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
    {
      "id": "filter_apply_again",
      "title": "再次应用上次滤镜",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "filter_apply_reprompt",
      "title": "再次应用上次滤镜（重新配置）",
      "implemented": true,
      "source": "krita/krita5.xmlgui",
      "parameters": [],
      "documentWrite": true
    },
    {
      "id": "filter_gallery",
      "title": "滤镜库…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
    },
    {
      "separator": true
    },
    {
      "id": "adjust_filters",
      "title": "调整",
      "children": [
        {
          "id": "filter.hsv_adjustment",
          "title": "HSV 调整…",
          "implemented": false,
          "source": "plugins/filters/colorsfilters/kis_hsv_adjustment_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.dodge",
          "title": "减淡…",
          "implemented": false,
          "source": "plugins/filters",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.burn",
          "title": "加深…",
          "implemented": false,
          "source": "plugins/filters",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.desaturate",
          "title": "去色…",
          "implemented": true,
          "source": "plugins/filters/colorsfilters/kis_desaturate_filter.cpp",
          "parameters": [
            {
              "name": "mode",
              "type": "string",
              "description": "去色算法",
              "default": "lightness",
              "choices": [
                "lightness",
                "luminosity",
                "luminosity601",
                "average",
                "min",
                "max"
              ]
            }
          ],
          "documentWrite": true
        },
        {
          "id": "filter.invert",
          "title": "反相",
          "implemented": true,
          "source": "plugins/filters/example/example.cpp",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "filter.slope_offset_power",
          "title": "斜率、偏移、幂 (ASC-CDL)…",
          "implemented": false,
          "source": "plugins/filters/asccdl/kis_asccdl_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.auto_contrast",
          "title": "自动对比度…",
          "implemented": false,
          "source": "plugins/filters/colorsfilters/colorsfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.color_balance",
          "title": "色彩平衡…",
          "implemented": false,
          "source": "plugins/filters/colorsfilters/kis_color_balance_filter.cpp",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        },
        {
          "id": "filter.levels",
          "title": "色阶…",
          "implemented": false,
          "source": "plugins/filters/levelfilter/KisLevelsFilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.cross_channel_adjustment_curves",
          "title": "跨通道调整曲线…",
          "implemented": false,
          "source": "plugins/filters",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.threshold",
          "title": "阈值…",
          "implemented": true,
          "source": "plugins/filters/threshold/threshold.cpp",
          "parameters": [
            {
              "name": "threshold",
              "type": "integer",
              "description": "阈值（0–255）",
              "default": 128
            }
          ],
          "documentWrite": true
        },
        {
          "id": "filter.color_adjustment_curves",
          "title": "颜色调整曲线…",
          "implemented": false,
          "source": "plugins/filters",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        }
      ]
    },
    {
      "id": "artistic_filters",
      "title": "艺术效果",
      "children": [
        {
          "id": "filter.pixelize",
          "title": "像素化…",
          "implemented": false,
          "source": "plugins/filters/pixelizefilter/kis_pixelize_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.halftone",
          "title": "半色调…",
          "implemented": false,
          "source": "plugins/filters/halftone/KisHalftoneFilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.oilpaint",
          "title": "油画…",
          "implemented": false,
          "source": "plugins/filters/oilpaintfilter/kis_oilpaint_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.index_colors",
          "title": "索引颜色…",
          "implemented": false,
          "source": "plugins/filters/indexcolors/indexcolors.cpp",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        },
        {
          "id": "filter.posterize",
          "title": "色调分离…",
          "implemented": true,
          "source": "plugins/filters/posterize/posterize.cpp",
          "parameters": [
            {
              "name": "steps",
              "type": "integer",
              "description": "量化步数（2–128；包含透明度通道）",
              "default": 16
            }
          ],
          "documentWrite": true
        },
        {
          "id": "filter.raindrops",
          "title": "雨滴…",
          "implemented": false,
          "source": "plugins/filters/raindropsfilter/kis_raindrops_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "blur_filters",
      "title": "模糊",
      "children": [
        {
          "id": "filter.blur",
          "title": "模糊…",
          "implemented": false,
          "source": "plugins/filters/blur/kis_blur_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.motion_blur",
          "title": "运动模糊…",
          "implemented": false,
          "source": "plugins/filters/blur/kis_motion_blur_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.lens_blur",
          "title": "镜头模糊…",
          "implemented": false,
          "source": "plugins/filters/blur/kis_lens_blur_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.gaussian_blur",
          "title": "高斯模糊…",
          "implemented": false,
          "source": "plugins/filters/blur/kis_gaussian_blur_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "color_filters",
      "title": "颜色",
      "children": [
        {
          "id": "filter.fast_color_overlay",
          "title": "快速颜色叠加…",
          "implemented": false,
          "source": "plugins/filters/colors/KisFilterFastColorOverlay.cpp",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        },
        {
          "id": "filter.propagate_colors",
          "title": "扩散颜色…",
          "implemented": false,
          "source": "plugins/filters",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        },
        {
          "id": "filter.maximize",
          "title": "最大化通道",
          "implemented": true,
          "source": "plugins/filters/colors/kis_minmax_filters.cpp",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "filter.minimize",
          "title": "最小化通道",
          "implemented": true,
          "source": "plugins/filters/colors/kis_minmax_filters.cpp",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "filter.color_to_alpha",
          "title": "颜色转透明度…",
          "implemented": false,
          "source": "plugins/filters/colors/kis_color_to_alpha.cpp",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "filter.color_transfer",
          "title": "颜色迁移…",
          "implemented": false,
          "source": "plugins/filters/fastcolortransfer/fastcolortransfer.cpp",
          "reason": "尚缺对应色彩空间/色彩管理模型"
        }
      ]
    },
    {
      "id": "decor_filters",
      "title": "装饰",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    },
    {
      "id": "edge_filters",
      "title": "边缘检测",
      "children": [
        {
          "id": "filter.top_edge_detection",
          "title": "上边缘检测…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.edge_detection",
          "title": "边缘检测…",
          "implemented": false,
          "source": "plugins/filters/edgedetection/kis_edge_detection_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.height_to_normal_map",
          "title": "高度转法线贴图…",
          "implemented": false,
          "source": "plugins/filters/convertheightnormalmap/kis_convert_height_to_normal_map_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.gaussian_high_pass",
          "title": "高斯高通…",
          "implemented": false,
          "source": "plugins/filters/gaussianhighpass/gaussianhighpass_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "emboss_filters",
      "title": "浮雕",
      "children": [
        {
          "id": "filter.emboss_horizontal_vertical",
          "title": "Emboss Horizontal  Vertical…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.emboss_in_all_directions",
          "title": "全方向浮雕…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.emboss_with_variable_depth",
          "title": "可变深度浮雕…",
          "implemented": false,
          "source": "plugins/filters/embossfilter/kis_emboss_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.emboss_vertical_only",
          "title": "垂直浮雕…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.emboss_laplacian",
          "title": "拉普拉斯浮雕…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.emboss_horizontal_only",
          "title": "水平浮雕…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "enhance_filters",
      "title": "增强",
      "children": [
        {
          "id": "filter.mean_removal",
          "title": "去除均值…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.unsharp_mask",
          "title": "反锐化蒙版…",
          "implemented": false,
          "source": "plugins/filters/unsharp/kis_unsharp_filter.cpp",
          "reason": "尚缺像素选区/蒙版模型与对应算法"
        },
        {
          "id": "filter.wavelet_noise_reducer",
          "title": "小波降噪…",
          "implemented": false,
          "source": "plugins/filters/imageenhancement/kis_wavelet_noise_reduction.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.sharpen",
          "title": "锐化…",
          "implemented": false,
          "source": "plugins/filters/convolutionfilters/convolutionfilters.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.gaussian_noise_reduction",
          "title": "高斯降噪…",
          "implemented": false,
          "source": "plugins/filters/imageenhancement/kis_simple_noise_reducer.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "map_filters",
      "title": "映射",
      "children": [
        {
          "id": "filter.phong_bumpmap",
          "title": "Phong 凹凸贴图…",
          "implemented": false,
          "source": "plugins/filters/phongbumpmap/kis_phong_bumpmap_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.round_corners",
          "title": "圆角…",
          "implemented": false,
          "source": "plugins/filters/roundcorners/kis_round_corners_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.small_tiles",
          "title": "小瓷砖…",
          "implemented": false,
          "source": "plugins/filters/smalltilesfilter/kis_small_tiles_filter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.normalize",
          "title": "归一化…",
          "implemented": false,
          "source": "plugins/filters/normalize/kis_normalize.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.gradient_map",
          "title": "渐变映射…",
          "implemented": false,
          "source": "plugins/filters/gradientmap/KisGradientMapFilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.palettize",
          "title": "调色板量化…",
          "implemented": false,
          "source": "plugins/filters/palettize/palettize.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "id": "nonphotorealistic_filters",
      "title": "非真实感",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚缺对应工程数据或独立功能实现"
    },
    {
      "id": "other_filters",
      "title": "其他",
      "children": [
        {
          "id": "filter.wave",
          "title": "波浪…",
          "implemented": false,
          "source": "plugins/filters/wavefilter/wavefilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.resettransparent",
          "title": "重置透明像素",
          "implemented": true,
          "source": "plugins/filters/resettransparent/KisResetTransparentFilter.cpp",
          "parameters": [],
          "documentWrite": true
        },
        {
          "id": "filter.random_noise",
          "title": "随机噪点…",
          "implemented": false,
          "source": "plugins/filters/noisefilter/noisefilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        },
        {
          "id": "filter.random_pick",
          "title": "随机拾取…",
          "implemented": false,
          "source": "plugins/filters/randompickfilter/randompickfilter.cpp",
          "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
        }
      ]
    },
    {
      "separator": true
    },
    {
      "id": "QMic",
      "title": "G’MIC-Qt…",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
    },
    {
      "id": "QMicAgain",
      "title": "再次应用 G’MIC-Qt",
      "implemented": false,
      "source": "krita/krita5.xmlgui",
      "reason": "尚未接入此滤镜的处理算法、参数/预设或 G’MIC 引擎"
    }
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
          "id": "docker.pending.animation",
          "title": "动画时间轴",
          "implemented": false,
          "source": "krita/krita5.xmlgui",
          "reason": "尚缺对应工程数据或独立功能实现"
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
            "mirrorAllNodesX","mirrorAllNodesY" -> result(state.optJSONObject("selection")==null && ArtMenuOperations.layers(state).isNotEmpty() && ArtMenuOperations.layers(state).all { it.optString("parentId").isBlank() && it.getString("kind") in setOf("paint","image") && it.getBoolean("visible") && !it.getBoolean("locked") && it.getDouble("x")==0.0 && it.getDouble("y")==0.0 && it.getDouble("scale")==1.0 && it.getDouble("rotation")==0.0 },"整体翻转目前需要全部为可见、未锁定、无变换的根像素层，且无选区")
            "rotateAllLayers","rotateAllLayersCW90","rotateAllLayersCCW90","rotateAllLayers180" -> result(ArtMenuOperations.layers(state).isNotEmpty() && ArtMenuOperations.layers(state).none { ArtMenuOperations.isLocked(state,it) },"请先解锁所有图层")
            "cut_selection_to_new_layer" -> result(state.optJSONObject("selection")!=null && ArtMenuOperations.pixelsEditable(state),"请选择可编辑根像素图层并创建选区")
            "copy_selection_to_new_layer" -> result(state.optJSONObject("selection")!=null && hasLayer && root && selected!!.getString("kind") in setOf("paint","image") && selected.getBoolean("visible"),"请选择可见根像素图层并创建选区")
            "save_groups_as_images" -> result(ArtMenuOperations.layers(state).any { it.getString("kind")=="group" && it.optString("parentId").isBlank() },"尚无根图层组")
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
    fun help(action:String,context:JSONObject):JSONObject = when(action) {
        "buginfo","sysinfo" -> JSONObject().put("pluginVersion",VERSION).put("androidRelease",Build.VERSION.RELEASE)
            .put("sdk",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("supportedAbis",JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("document",context.optJSONObject("document")?.let { snap -> JSONObject().put("id",snap.getString("id"))
                .put("revision",snap.getInt("revision")).put("width",snap.getJSONObject("state").getInt("width"))
                .put("height",snap.getJSONObject("state").getInt("height")) } ?: JSONObject.NULL)
        "help_about_app" -> JSONObject().put("title","AI Limbs 画室 $VERSION")
            .put("text","阿伟与兰儿共用工程、图层和操作历史。菜单参考 Krita 6.0.4；当前使用画室自己的 RGB 8 位工程与 Android 渲染，不含 Krita/Qt 引擎。")
        "help_whats_this" -> JSONObject().put("title","这是什么？").put("text","画布工具记录可撤销操作；右侧足迹可切换历史状态。菜单灰色表示当前不可执行，menu.catalog 返回具体原因。图层、笔刷和拾色器面板共用同一工程。")
        "help_show_tip" -> JSONObject().put("title","画室提示").put("text","协作编辑前先读取工程 revision；兰儿菜单写操作携带 documentId 与 expectedRevision，避免把阿伟的新修改覆盖掉。右侧足迹可恢复合并或滤镜之前的状态。")
        else -> JSONObject().put("title","画室手册").put("text","新建或打开工程后，通过左侧工具作画，右侧管理图层与足迹。文件→保存写入 .ailart；导出写入 PNG/JPEG。图层剪贴板复制完整图层树，与编辑菜单的像素剪贴板分开。基础滤镜作用于当前可编辑根图层并遵循选区。兰儿使用 menu.catalog 发现动作和参数，再通过 menu.execute 执行同一个动作。")
    }
}
