# Generated by Django 3.0.5 on 2020-06-05 21:56

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('indoorAppServer', '0006_fingerprint_zone'),
    ]

    operations = [
        migrations.AlterField(
            model_name='fingerprint',
            name='coordinate_X',
            field=models.FloatField(blank=True),
        ),
        migrations.AlterField(
            model_name='fingerprint',
            name='coordinate_Y',
            field=models.FloatField(blank=True),
        ),
    ]
