"""BBox-level copy-paste augmentation for mmdet 3.x pipelines.

Segmentation masks aren't available in this dataset (COCO json has no
`segmentation` field), so a proper mask-based CopyPaste isn't possible.
This pastes rectangular bbox crops instead — a deliberate simplification
(includes background pixels within the crop) used to increase visual
diversity/context exposure for an under-performing class, following the
same cached-sample pattern as mmdet's CachedMosaic/CachedMixUp.
"""
import copy
import random

import mmcv
import numpy as np
from mmcv.transforms import BaseTransform
from mmcv.transforms.utils import cache_randomness

from mmdet.registry import TRANSFORMS
from mmdet.structures.bbox import HorizontalBoxes, autocast_box_type


@TRANSFORMS.register_module()
class CachedBBoxCopyPaste(BaseTransform):
    """Paste cached bbox crops of a target class onto the current image.

    Required Keys:
    - img
    - gt_bboxes
    - gt_bboxes_labels
    - gt_ignore_flags

    Modified Keys:
    - img
    - gt_bboxes
    - gt_bboxes_labels
    - gt_ignore_flags

    Args:
        target_category_ids (Sequence[int]): category ids eligible as
            paste sources (indices into the dataset's category_id, not
            the internal 0-based label — mmdet remaps labels to 0-based
            during LoadAnnotations, so pass 0-based label ids here).
        max_paste (int): max number of instances pasted per image.
        scale_range (Tuple[float, float]): random resize jitter applied
            to each pasted crop.
        max_cached_images (int): cache size, mirrors CachedMixUp.
        max_iters (int): retries when a cache entry has no eligible
            instance or a paste placement can't be found.
        prob (float): probability of applying this transform per image.
    """

    def __init__(self,
                 target_category_ids,
                 max_paste: int = 2,
                 scale_range=(0.7, 1.3),
                 max_cached_images: int = 30,
                 max_iters: int = 10,
                 prob: float = 0.5) -> None:
        assert max_cached_images >= 2
        self.target_category_ids = set(target_category_ids)
        self.max_paste = max_paste
        self.scale_range = scale_range
        self.max_cached_images = max_cached_images
        self.max_iters = max_iters
        self.prob = prob
        self.results_cache = []

    @cache_randomness
    def _pick_instance(self, cache):
        for _ in range(self.max_iters):
            entry = cache[random.randint(0, len(cache) - 1)]
            labels = entry['gt_bboxes_labels']
            eligible = [i for i, lb in enumerate(labels)
                        if lb in self.target_category_ids]
            if eligible:
                return entry, random.choice(eligible)
        return None, None

    @autocast_box_type()
    def transform(self, results: dict) -> dict:
        self.results_cache.append(copy.deepcopy(results))
        if len(self.results_cache) > self.max_cached_images:
            self.results_cache.pop(random.randint(0, len(self.results_cache) - 1))

        if len(self.results_cache) <= 1 or random.uniform(0, 1) > self.prob:
            return results

        img = results['img'].copy()
        h, w = img.shape[:2]

        pasted_boxes, pasted_labels = [], []
        n_paste = random.randint(1, self.max_paste)
        for _ in range(n_paste):
            entry, idx = self._pick_instance(self.results_cache)
            if entry is None:
                continue

            src_img = entry['img']
            src_box = entry['gt_bboxes'].numpy()[idx]
            x1, y1, x2, y2 = [int(round(v)) for v in src_box]
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(src_img.shape[1], x2), min(src_img.shape[0], y2)
            if x2 - x1 < 4 or y2 - y1 < 4:
                continue
            crop = src_img[y1:y2, x1:x2]

            jit = random.uniform(*self.scale_range)
            new_w = max(4, int(round((x2 - x1) * jit)))
            new_h = max(4, int(round((y2 - y1) * jit)))
            new_w, new_h = min(new_w, w), min(new_h, h)
            crop = mmcv.imresize(crop, (new_w, new_h))

            if random.uniform(0, 1) > 0.5:
                crop = crop[:, ::-1]

            if w - new_w <= 0 or h - new_h <= 0:
                continue
            px = random.randint(0, w - new_w)
            py = random.randint(0, h - new_h)
            img[py:py + new_h, px:px + new_w] = crop

            pasted_boxes.append([px, py, px + new_w, py + new_h])
            pasted_labels.append(entry['gt_bboxes_labels'][idx])

        if not pasted_boxes:
            return results

        pasted_boxes = HorizontalBoxes(np.array(pasted_boxes, dtype=np.float32))
        results['img'] = img
        results['gt_bboxes'] = results['gt_bboxes'].cat(
            (results['gt_bboxes'], pasted_boxes), dim=0)
        results['gt_bboxes_labels'] = np.concatenate(
            (results['gt_bboxes_labels'], np.array(pasted_labels, dtype=np.int64)),
            axis=0)
        results['gt_ignore_flags'] = np.concatenate(
            (results['gt_ignore_flags'], np.zeros(len(pasted_boxes), dtype=bool)),
            axis=0)
        return results

    def __repr__(self):
        return (f'{self.__class__.__name__}('
                f'target_category_ids={self.target_category_ids}, '
                f'max_paste={self.max_paste}, prob={self.prob})')
