#%%
from typing import *

import numpy as np

import matplotlib.pyplot as plt
import seaborn as sns
#%%
import pandas as pd

from dataset.our_dataset import *
dataset = get_our_dataset()
dataframe = pd.DataFrame.from_records([w.to_dict() for w in dataset])
#%%
min_framerate = 11025
#%%
import librosa
import librosa.feature

from scipy import signal

from features import *

def stereo_to_mono(data):
    if (len(data.shape) == 2 and data.shape[1] > 1):
        data = [np.sum(i) / data.shape[1] for i in data]
    return data

def get_mfccs(
    dataframe,
    **kwargs):
    x = list()
    for index, row in dataframe.iterrows():
        data = stereo_to_mono(row.data)
        sig = np.array([float(i) for i in data])
        mfccs = librosa.feature.mfcc(
            sig,
            row.framerate,
            n_mfcc=kwargs.get('n_mfcc', 40),
            n_fft=kwargs.get('n_fft', 4096),
            **kwargs)
        x.append(mfccs)

    return x

def pad_features(mfccs, time_rows_count):
    n_mfccs = mfccs.shape[0]
    mfccs_time_rows = mfccs.shape[1]
    if (mfccs_time_rows < time_rows_count):
        pad_width = time_rows_count - mfccs_time_rows
        mfccs = np.pad(mfccs, pad_width=((0, 0), (0, pad_width)), mode='constant')
    else:
        mfccs = mfccs[:, 0:time_rows_count]
    return mfccs
mfccs_time_size = 150

X_2d_mfccs = get_mfccs(dataframe)

X_2d_mfccs_padded = [pad_features(mfccs, mfccs_time_size) for mfccs in X_2d_mfccs]
X_1d_mfccs = pd.DataFrame([np.concatenate(get_features1d(feature2d), axis=None) for feature2d in X_2d_mfccs])
y = dataframe['cough_type']
#%%
def get_scalogram(
    dataframe,
    widths,
    **kwargs):
    x = list()
    for index, row in dataframe.iterrows():
        data = stereo_to_mono(row.data)
        sig = np.array([float(i) for i in data])
        scalogram = signal.cwt(sig, signal.ricker, widths)
        x.append(scalogram)

    return x

scalogram_widths = np.arange(1, 21)
scalogram_scale_size = len(scalogram_widths)
scalogram_time_size = 150000

X_2d_scalogram = get_scalogram(dataframe, scalogram_widths)
X_2d_scalogram_padded = [pad_features(scalogram, scalogram_time_size) for scalogram in X_2d_scalogram]
#%%
from sklearn import preprocessing
from sklearn.metrics import *
from sklearn.model_selection import *

test_size = 0.1
validate_size = 0.15
def dataframe_split(df, seed = None) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    df_train, df_test = train_test_split(
        df,
        test_size=test_size+validate_size,
        random_state=seed)
    df_test, df_validate = train_test_split(
        df_test,
        test_size=validate_size,
        random_state=seed)
    return df_train, df_validate, df_test

def X_y_split(X, y, seed = None):
    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=test_size+validate_size,
        random_state=seed)
    X_test, X_validate, y_test, y_validate = train_test_split(
        X_test,
        y_test,
        test_size=validate_size,
        random_state=seed)
    return X_train, X_validate, X_test, y_train, y_validate, y_test
#%%
seed = 777
X_train_1d_mfccs, X_validate_1d_mfccs, X_test_1d_mfccs, y_train, y_validate, y_test = X_y_split(X_1d_mfccs, y, seed=seed)
X_train_2d_mfccs, X_validate_2d_mfccs, X_test_2d_mfccs, _, _, _ = X_y_split(X_2d_mfccs, y, seed=seed)
X_train_2d_mfccs_padded, X_validate_2d_mfccs_padded, X_test_2d_mfccs_padded, _, _, _ = X_y_split(X_2d_mfccs_padded, y, seed=seed)
X_train_2d_scalogram, X_validate_2d_scalogram, X_test_2d_scalogram, _, _, _ = X_y_split(X_2d_scalogram, y, seed=seed)
X_train_2d_scalogram_padded, X_validate_2d_scalogram_padded, X_test_2d_scalogram_padded, _, _, _ = X_y_split(X_2d_scalogram_padded, y, seed=seed)
#%%
class_weights = {0:1, 1:5, 2:5, 3:7}
class_weights_arr = [1, 5, 5, 7]
#%%
def plot_confusion(confusion):
    labels = ['Normal', 'Wet', 'Whistling', 'Covid']
    sns.heatmap(confusion, annot=True,
        xticklabels=labels, yticklabels=labels)
    plt.show()
#%%
from sklearn.ensemble import RandomForestClassifier

clf = RandomForestClassifier(class_weight=class_weights)
clf.fit(X_train_1d_mfccs, y_train)
y_random_forest_pred = clf.predict(X_test_1d_mfccs)
print(f'Random Forest {classification_report(y_test, y_random_forest_pred)}')
plot_confusion(confusion_matrix(y_test, y_random_forest_pred, normalize='true'))
#%%
import torch

if torch.cuda.is_available():
    device = torch.device('cuda')
else:
    device = torch.device('cpu')
#%%
def train_test(
    model,
    checkpoint_path,
    X_train, X_validate, X_test,
    y_train, y_validate, y_test,
    seed = None,
    silent = True,
    epochs=1000,
    **kwargs):
    checkpoint_path = f'{checkpoint_path}.pt'
    np.random.seed(seed)
    manual_seed = seed != None
    torch.backends.cudnn.deterministic = manual_seed
    torch.backends.cudnn.benchmark = not manual_seed
    if (manual_seed):
        torch.manual_seed(seed)
    else:
        torch.seed()

    data_to_tensor = lambda X_data: X_data.values if type(X_data) == pd.DataFrame else X_data

    X_train_torch = torch.tensor(data_to_tensor(X_train)).float().to(device)
    X_validate_torch = torch.tensor(data_to_tensor(X_validate)).float().to(device)
    X_test_torch = torch.tensor(data_to_tensor(X_test)).float().to(device)
    y_train_torch = torch.tensor(y_train.values).to(device)
    y_test_torch = torch.tensor(y_test.values).to(device)
    y_validate_torch = torch.tensor(y_validate.values).to(device)

    weights = torch.FloatTensor(class_weights_arr).to(device)
    loss_fn = torch.nn.CrossEntropyLoss(weights)

    optimizer = torch.optim.AdamW(model.parameters())

    train_losses = []
    train_accs = []
    val_losses = []
    val_accs = []
    for epoch in range(epochs):
        optimizer.zero_grad()
        y_train_pred_torch = model(X_train_torch)
        train_loss = loss_fn(y_train_pred_torch, y_train_torch)
        train_losses.append(train_loss.item())

        _, y_train_pred = torch.max(y_train_pred_torch.cpu(), 1)
        train_acc = accuracy_score(y_train, y_train_pred)
        train_accs.append(train_acc)

        train_loss.backward()
        optimizer.step()

        with torch.no_grad():
            y_validate_pred_torch = model(X_validate_torch)
            val_loss = loss_fn(y_validate_pred_torch, y_validate_torch)
            val_losses.append(val_loss.item())

            _, y_validate_pred = torch.max(y_validate_pred_torch.cpu(), 1)
            val_acc = accuracy_score(y_validate, y_validate_pred)
            val_accs.append(val_acc)

        if (val_loss.item() <= np.min(val_losses)):
            torch.save(model.state_dict(), checkpoint_path)
            if (silent):
                continue
            print(f'{epoch} saved')
            print(f'{epoch} Loss {train_loss} {val_loss}')
            print(f'{epoch} Accuracy {train_acc} {val_acc}')
        elif (epoch % 100 == 99):
            if (silent):
                continue
            print(f'{epoch} Loss {train_loss} {val_loss}')
            print(f'{epoch} Accuracy {train_acc} {val_acc}')

    model.load_state_dict(torch.load(checkpoint_path))
    model.eval()

    y_test_pred_torch = model(X_test_torch)
    test_loss = loss_fn(y_test_pred_torch, y_test_torch)
    _, y_test_pred = torch.max(y_test_pred_torch, 1)
    y_test_pred_cpu = y_test_pred.cpu()

    confusion = confusion_matrix(y_test, y_test_pred_cpu, normalize='true')
    accuracy = accuracy_score(y_test, y_test_pred_cpu)

    if not silent:
        plt.plot(train_losses, 'r', val_losses, 'b')
        plt.legend(['Train', 'Validate'])
        plt.show()

        plt.plot(train_accs, 'r', val_accs, 'b')
        plt.legend(['Train', 'Validate'])
        plt.show()

        plot_confusion(confusion)

        print(classification_report(y_test, y_test_pred_cpu))

    return \
        confusion, \
        accuracy, \
        test_loss.item(), \
        train_losses, train_accs, \
        val_losses, val_accs

from torch.utils.data import DataLoader

def train_test_dl(
    model,
    checkpoint_path,
    train_dl: DataLoader,
    validate_dl: DataLoader,
    test_dl: DataLoader,
    seed = None,
    silent = True,
    epochs=1000,
    **kwargs):
    checkpoint_path = f'{checkpoint_path}.pt'
    np.random.seed(seed)
    manual_seed = seed != None
    torch.backends.cudnn.deterministic = manual_seed
    torch.backends.cudnn.benchmark = not manual_seed
    if (manual_seed):
        torch.manual_seed(seed)
    else:
        torch.seed()

    weights = torch.FloatTensor(class_weights_arr).to(device)
    loss_fn = torch.nn.CrossEntropyLoss(weights)

    optimizer = torch.optim.AdamW(model.parameters())

    train_losses = []
    train_accs = []
    val_losses = []
    val_accs = []
    for epoch in range(epochs):
        optimizer.zero_grad()
        train_correct = 0
        train_loss = 0
        train_total = 0
        for X_train, y_train in train_dl:
            X_train_torch = X_train.float().to(device)
            y_train_torch = y_train.to(device)

            y_train_pred_torch = model(X_train_torch)
            train_loss_torch = loss_fn(y_train_pred_torch, y_train_torch)

            train_loss += train_loss_torch.item()
            _, y_train_pred = torch.max(y_train_pred_torch.cpu(), 1)
            train_correct += (y_train_pred == y_train).float().sum()
            train_total += y_train.shape[0]

            train_loss_torch.backward()
            optimizer.step()
        train_losses.append(train_loss)
        train_acc = train_correct / train_total
        train_accs.append(train_acc)

        with torch.no_grad():
            val_correct = 0
            val_loss = 0
            val_total = 0
            for X_validate, y_validate in validate_dl:
                X_validate_torch = X_validate.float().to(device)
                y_validate_torch = y_validate.to(device)

                y_validate_pred_torch = model(X_validate_torch)

                val_loss += loss_fn(y_validate_pred_torch, y_validate_torch).item()
                _, y_validate_pred = torch.max(y_validate_pred_torch.cpu(), 1)
                val_correct += (y_validate_pred == y_validate).float().sum()
                val_total += y_validate.shape[0]
            val_losses.append(val_loss)
            val_acc = val_correct / val_total
            val_accs.append(val_acc)

            if (val_loss <= np.min(val_losses)):
                torch.save(model.state_dict(), checkpoint_path)
                if (silent):
                    continue
                print(f'{epoch} saved')
                print(f'{epoch} Loss {train_loss} {val_loss}')
                print(f'{epoch} Accuracy {train_acc} {val_acc}')
            elif (epoch % 100 == 99):
                if (silent):
                    continue
                print(f'{epoch} Loss {train_loss} {val_loss}')
                print(f'{epoch} Accuracy {train_acc} {val_acc}')

    model.load_state_dict(torch.load(checkpoint_path))
    model.eval()

    test_loss = 0
    y_test = []
    y_test_pred_cpu = []
    for X_test, y_test_batch in test_dl:
        X_test_torch = X_test.float().to(device)
        y_test_torch = y_test_batch.to(device)

        y_test_pred_torch = model(X_test_torch)

        test_loss += loss_fn(y_test_pred_torch, y_test_torch).item()
        _, y_test_pred = torch.max(y_test_pred_torch.cpu(), 1)
        y_test += y_test_batch.cpu()
        y_test_pred_cpu += y_test_pred

    confusion = confusion_matrix(y_test, y_test_pred_cpu, normalize='true')
    accuracy = accuracy_score(y_test, y_test_pred_cpu)

    if not silent:
        plt.plot(train_losses, 'r', val_losses, 'b')
        plt.legend(['Train', 'Validate'])
        plt.show()

        plt.plot(train_accs, 'r', val_accs, 'b')
        plt.legend(['Train', 'Validate'])
        plt.show()

        plot_confusion(confusion)

        print(classification_report(y_test_batch, y_test_pred_cpu))

    return \
        confusion, \
        accuracy, \
        test_loss, \
        train_losses, train_accs, \
        val_losses, val_accs
#%%
from models.linear_net import CoughNetLinear
linear_net = CoughNetLinear(40 * 7, 4).to(device)
linear_net_train_result = train_test(
    linear_net,
    'linear_net_checkpoint',
    X_train_1d_mfccs, X_validate_1d_mfccs, X_test_1d_mfccs,
    y_train, y_validate, y_test,
    silent=False)
#%%
from models.cnn_net import CoughNetCnn
cnn_net = CoughNetCnn(40, mfccs_time_size, 4).to(device)
cnn_net_train_result = train_test(
    cnn_net,
    'cnn_net_checkpoint',
    X_train_2d_mfccs_padded, X_validate_2d_mfccs_padded, X_test_2d_mfccs_padded,
    y_train, y_validate, y_test,
    silent=False)
#%%
from models.cnn_lstm_net import CoughNetCnnLstm
cnn_lstm_net = CoughNetCnnLstm(40, mfccs_time_size, 4,
    lstm_hidden_size=150).to(device)
cnn_lstm_net_train_result = train_test(
    cnn_lstm_net,
    'cnn_lstm_net_checkpoint',
    X_train_2d_mfccs_padded, X_validate_2d_mfccs_padded, X_test_2d_mfccs_padded,
    y_train, y_validate, y_test,
    silent=False)
#%%
from torch.utils.data import DataLoader, Dataset

class DataframeCoughDataset(Dataset):
    def __init__(self, X, y=None):
        self.X = X
        self.y = y

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        x = self.X.iloc[idx]
        if self.y is None:
            y = -1
        else:
            y = self.y.iloc[idx]
        return x, y

class ListCoughDataset(Dataset):
    def __init__(self, X, y=None):
        self.X = X
        self.y = y

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        x = self.X[idx]
        if self.y is None:
            y = -1
        else:
            y = self.y.iloc[idx]
        return x, y

train_2d_scalogram_padded_ds = ListCoughDataset(X_train_2d_scalogram_padded, y_train)
validate_2d_scalogram_padded_ds = ListCoughDataset(X_validate_2d_scalogram_padded, y_validate)
test_2d_scalogram_padded_ds = ListCoughDataset(X_test_2d_scalogram_padded, y_test)

batch_size_2d_scalogram_padded = 1
train_2d_scalogram_padded_dl = DataLoader(train_2d_scalogram_padded_ds, batch_size=batch_size_2d_scalogram_padded, shuffle=True)
validate_2d_scalogram_padded_dl = DataLoader(validate_2d_scalogram_padded_ds, batch_size=batch_size_2d_scalogram_padded, shuffle=True)
test_2d_scalogram_padded_dl = DataLoader(test_2d_scalogram_padded_ds, batch_size=batch_size_2d_scalogram_padded, shuffle=True)

scalogram_epochs = 100
#%%
from models.cnn_net import CoughNetCnn
cnn_net = CoughNetCnn(scalogram_scale_size, scalogram_time_size, 4).to(device)
cnn_net_train_result = train_test_dl(
    cnn_net,
    'cnn_scalogram_net_checkpoint',
    train_2d_scalogram_padded_dl,
    validate_2d_scalogram_padded_dl,
    test_2d_scalogram_padded_dl,
    silent=False,
    epochs=scalogram_epochs)
#%%
from models.cnn_lstm_net import CoughNetCnnLstm
cnn_lstm_net = CoughNetCnnLstm(scalogram_scale_size, scalogram_time_size, 4,
    lstm_hidden_size=150).to(device)
cnn_lstm_net_train_result = train_test_dl(
    cnn_lstm_net,
    'cnn_lstm_scalogram_net_checkpoint',
    train_2d_scalogram_padded_dl,
    validate_2d_scalogram_padded_dl,
    test_2d_scalogram_padded_dl,
    silent=False,
    epochs=scalogram_epochs)
#%%
