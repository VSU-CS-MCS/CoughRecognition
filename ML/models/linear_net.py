#%%
from typing import *

import torch
import torch.nn as nn
import torch.nn.functional as F
#%%
class CoughNetLinear(nn.Module):
    def __init__(
        self,
        feature_count,
        n_classes,
        dropout = 0.1,
        units = 64,
        layers = 5):
        super(CoughNetLinear, self).__init__()

        model_args = [
            torch.nn.Linear(feature_count, units),
            torch.nn.SELU(),
            torch.nn.AlphaDropout(dropout),
        ]
        for layer_index in range(layers - 1):
            model_args.extend([
                torch.nn.Linear(units, units),
                torch.nn.SELU(),
                torch.nn.AlphaDropout(dropout)
            ])
        model_args.append(torch.nn.Linear(units, n_classes))
        self.linear_layers = torch.nn.Sequential(*model_args)

    def forward(self, x):
        y = self.linear_layers(x)
        return y
