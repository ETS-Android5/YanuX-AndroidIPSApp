from django.shortcuts import render
from .models import Fingerprint, DeviceSensor, BluetoothSensor, WiFiSensor
from rest_framework import viewsets
from .serializers import FingerprintSerializer, DeviceDataSerializer, WifiDataSerializer, BluetoothSerializer
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView
import os.path
from os import path
import pandas as pd
import numpy as np
from IPython.core.display import display
from enum import Enum
from .snippets import filters, convertJson,fingerprintPositioning, proximityPositioning
import json


class FingerprintView(viewsets.ModelViewSet):
    queryset = Fingerprint.objects.all()
    serializer_class = FingerprintSerializer


class DeviceView(viewsets.ModelViewSet):
    queryset = DeviceSensor.objects.all()
    serializer_class = DeviceDataSerializer


class WifiView(viewsets.ModelViewSet):
    queryset = WiFiSensor.objects.all()
    serializer_class = WifiDataSerializer


class BluetoothView(viewsets.ModelViewSet):
    queryset = BluetoothSensor.objects.all()
    serializer_class = BluetoothSerializer


class FilterEnum(Enum):
    MEDIAN_FILTER = 1
    MEAN_FILTER = 2


class TypeEnum(Enum):
    WIFI = 1
    BLUETOOTH = 2


def load_access_points_locations():
    with open('access_points_location.json') as json_file:
        data = json.load(json_file)
        access_points = {}
        for k, v in data.items():
            print('KEY: ' + k)
            access_points[k] = v
            print('X: ', v['x'])
            print('Y: ', v['y'])
            print('')
        return access_points

def compute_csv(request):
    csv_columns = ['coordinate_X', 'coordinate_Y', 'rssi_Value', 'rolling_mean_rssi', 'zone']
    sample = request.data
    if 'zone' in sample:
        zone = sample['zone']
    else:
        zone = ''
    single_value_scanned = sample['singleValue']
    valuesScanned = sample['values']
    aux_list = list()
    rolling_mean_list = list()
    for value in valuesScanned:
        aux_list.append(value)
        rolling_mean_list.append(np.mean(aux_list))
    print(rolling_mean_list)
    x_coordinate = sample['x_coordinate']
    y_coordinate = sample['y_coordinate']
    results_list_2d = list()
    for i in range(len(valuesScanned)):
        results_list = list()
        results_list.append(x_coordinate)
        results_list.append(y_coordinate)
        results_list.append(valuesScanned[i])
        results_list.append(rolling_mean_list[i])
        results_list.append(zone)
        results_list_2d.append(results_list)
    display(results_list_2d)
    df = pd.DataFrame(data=results_list_2d, columns=csv_columns)
    display(df)
    return df

def compute_csv_trilateration(sample,beacon_address):
    csv_columns = ['BLE Beacon','coordinate_X', 'coordinate_Y', 'rssi_Value', 'rolling_mean_rssi', 'zone']
    if 'zone' in sample:
        zone = sample['zone']
    else:
        zone = ''
    mac = beacon_address
    single_value_scanned = sample['singleValue']
    valuesScanned = sample['values']
    aux_list = list()
    rolling_mean_list = list()
    for value in valuesScanned:
        aux_list.append(value)
        rolling_mean_list.append(np.mean(aux_list))
    print(rolling_mean_list)
    x_coordinate = sample['x_coordinate']
    y_coordinate = sample['y_coordinate']
    results_list_2d = list()
    for i in range(len(valuesScanned)):
        results_list = list()
        results_list.append(mac)
        results_list.append(x_coordinate)
        results_list.append(y_coordinate)
        results_list.append(valuesScanned[i])
        results_list.append(rolling_mean_list[i])
        results_list.append(zone)
        results_list_2d.append(results_list)
    display(results_list_2d)
    df = pd.DataFrame(data=results_list_2d, columns=csv_columns)
    display(df)
    return df

class ProximityDistanceView(APIView):

    def post(self,request,formate=None):
        serializer_context = {
            'request': request,
        }
        df = compute_csv(request)
        if path.exists(".\dataset_test_university.csv"):
            df.to_csv(r'.\dataset_test_university.csv', mode='a',index=False, header=False)
        else:
            df.to_csv(r'.\dataset_test_university.csv',index=False, header=True)
        return Response(status=status.HTTP_200_OK)

class FilterView(APIView):

    def post(self, request, format=None):
        convertJson.jsonToFile('BluetoothWiFi')
        return Response(status=status.HTTP_200_OK)

    def apply_filter(self, filter_identifier, window_size):
        reference_points = Fingerprint.objects.raw(
            'SELECT indoorAppServer_fingerprint.id, indoorAppServer_fingerprint.coordinate_X,indoorAppServer_fingerprint.coordinate_Y, count(indoorAppServer_fingerprint.coordinate_X) FROM indoorAppServer_fingerprint GROUP BY indoorAppServer_fingerprint.coordinate_X, indoorAppServer_fingerprint.coordinate_Y')
        for rp in reference_points:
            fingerprints_per_reference_point = Fingerprint.objects.all().filter(coordinate_X=rp.coordinate_X,
                                                                                coordinate_Y=rp.coordinate_Y)
            number_fingerprints_per_reference_point = len(fingerprints_per_reference_point)
            number_partitions = int(number_fingerprints_per_reference_point / window_size)
            if number_partitions != number_fingerprints_per_reference_point:
                if filter_identifier == FilterEnum.MEDIAN_FILTER:
                    existing_fingerprint = fingerprints_per_reference_point.all()[:1]
                    filters.apply_median_filter(fingerprints_per_reference_point, TypeEnum.WIFI, existing_fingerprint)
                    filters.apply_median_filter(fingerprints_per_reference_point, TypeEnum.BLUETOOTH,
                                                existing_fingerprint)
                elif filter_identifier == FilterEnum.MEAN_FILTER:
                    existing_fingerprint = fingerprints_per_reference_point.all()[:1]
                    filters.apply_mean_filter(fingerprints_per_reference_point, TypeEnum.WIFI, existing_fingerprint)
                    filters.apply_mean_filter(fingerprints_per_reference_point, TypeEnum.BLUETOOTH,
                                              existing_fingerprint)

class TrilaterationHandlerView(APIView):


    def post(self,request,format=None):
        serializer_context = {
            'request': request,
        }
        room_limit_x_min = -2.0
        room_limit_x_max = 0.0
        room_limit_y_min = -1.5
        room_limit_y_max = 0.5
        access_points = load_access_points_locations()
        display(access_points)
        sample_dict = request.data
        print(sample_dict)
        distances = {}
        first_beacon = list(sample_dict.keys())[0]
        beacon_information = sample_dict[first_beacon]
        print(beacon_information)
        algorithm = beacon_information['algorithm']
        print(algorithm)
        if algorithm == 'KNN Regression':
            for k, v in sample_dict.items():
                df = compute_csv_trilateration(v, k)
                prediction_list = proximityPositioning.apply_knn_regressor(df)
                distances[k] = np.mean(prediction_list)
                print("PREDICTION")
                display(distances[k])
        elif algorithm == 'MLP Regression':
            for k, v in sample_dict.items():
                df = compute_csv_trilateration(v, k)
                prediction_list = proximityPositioning.apply_mlp_regressor(df)
                distances[k] = np.mean(prediction_list)
                print("PREDICTION")
                display(distances[k])
        elif algorithm == 'SVM Regressor':
            for k, v in sample_dict.items():
                df = compute_csv_trilateration(v, k)
                prediction_list = proximityPositioning.apply_svm_regressor(df)
                distances[k] = np.mean(prediction_list)
                print("PREDICTION")
                display(distances[k])
        elif algorithm == 'Linear Regression':
            for k, v in sample_dict.items():
                df = compute_csv_trilateration(v, k)
                prediction_list = proximityPositioning.apply_linear_regression(df)
                distances[k] = np.mean(prediction_list)
                print("PREDICTION")
                display(distances[k])
        print("DISTANCES ESTIMATIONS")
        print(distances)
        print("CHECKPOINT")
        for i in np.arange(room_limit_x_min,room_limit_x_max,0.5):
            for j in np.arange(room_limit_y_min,room_limit_y_max,0.5):
                self.compute_trilateration(x=i,y=j,access_points=access_points,distances=distances)

    def compute_trilateration(self,x,y,access_points,distances):
        mse = 0.0
        locations = []
        distances_list = []
        for k, v in access_points.items():
            locations.append((v['x'],v['y']))
        for k,v in distances.items():
            distances_list.append(v)
        for access_points, distances in zip(locations,distances_list):
            print(access_points)
            print(distances)

class ProximityAlgorithmsView(APIView):


    def post(self, request, format=None):
        isClassifier = False
        serializer_context = {
            'request': request,
        }
        sample = request.data
        df = compute_csv(request)
        prediction = []
        algorithm = sample['algorithm']
        if algorithm == 'KNN Regression':
            prediction = proximityPositioning.apply_knn_regressor(df)
        elif algorithm == 'KNN Classifier':
            prediction = proximityPositioning.apply_knn_classifier(df)
            isClassifier = True
        elif algorithm == 'MLP Regression':
            prediction = proximityPositioning.apply_mlp_regressor(df)
        elif algorithm == 'MLP Classifier':
            prediction = proximityPositioning.apply_mlp_classifier(df)
            isClassifier = True
        elif algorithm == 'SVM Classifier':
            prediction = proximityPositioning.apply_svm_classifier(df)
            isClassifier = True
        elif algorithm == 'SVM Regressor':
            prediction = proximityPositioning.apply_svm_regressor(df)
            isClassifier = True
        elif algorithm == 'Linear Regression':
            prediction = proximityPositioning.apply_linear_regression(df)
        elif algorithm == 'Random Forest Classifier':
            prediction = proximityPositioning.apply_randomForest_classifier(df)
            isClassifier = True

        return compute_Response(prediction,isClassifier,serializer_context)


class PositioningAlgorithmsView(APIView):


    def post(self, request, format=None):
        isClassifier = False
        serializer_context = {
            'request': request,
        }
        sample = request.data
        prediction = []
        filter = sample['filter']
        algorithm = sample['algorithm']
        dataTypes = sample['dataTypes']
        if filter == 'Mean':
            FilterView.apply_filter(FilterEnum.MEAN_FILTER,len(Fingerprint.objects.all()))
        elif filter == 'Median':
            FilterView.apply_filter(FilterEnum.MEDIAN_FILTER,len(Fingerprint.objects.all()))
        if algorithm == 'KNN Regression':
            prediction = fingerprintPositioning.apply_knn_regressor(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
        elif algorithm == 'KNN Classifier':
            prediction = fingerprintPositioning.apply_knn_classifier(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
            isClassifier = True
        elif algorithm == 'MLP Regression':
            prediction = fingerprintPositioning.apply_mlp_regressor(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
        elif algorithm == 'MLP Classifier':
            prediction = fingerprintPositioning.apply_mlp_classifier(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
            isClassifier = True
        elif algorithm == 'K-Means Classifier':
            prediction = fingerprintPositioning.apply_kmeans_knn_classifier(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
            isClassifier = True
        elif algorithm == 'SVM Classifier':
            prediction = fingerprintPositioning.apply_svm_classifier(dataTypes, sample['aps'], sample['beacons'], sample['deviceData'])
            isClassifier = True

        return compute_Response(prediction,isClassifier,serializer_context)



def compute_Response(prediction,isClassifier,serializer_context):
    print('prediction', prediction)
    if len(prediction) != 0:
        if isClassifier == True:
            fingerprint = Fingerprint.objects.create(coordinate_X=0.0, coordinate_Y=0.0, zone=prediction[0])
            print(fingerprint)
            serialized = FingerprintSerializer(fingerprint, context=serializer_context)
            return Response(serialized.data, status=status.HTTP_200_OK)
        else:
            fingerprint = Fingerprint.objects.create(coordinate_X=prediction[0][0], coordinate_Y=prediction[0][1])
            print(fingerprint)
            print(prediction)
            serialized = FingerprintSerializer(fingerprint, context=serializer_context)
            return Response(serialized.data, status=status.HTTP_200_OK)
    else:
        return Response(status=status.HTTP_500_INTERNAL_SERVER_ERROR)