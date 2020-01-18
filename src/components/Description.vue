<template>
    <div>

<h4>Значение слова</h4>

<div v-if="error" class="alert alert-danger alert-dismissible fade show" role="alert">
  Произошла ошибка при загрузке данных
  <button type="button" class="close" v-on:click="closeError" aria-label="Close">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div v-if="warning" class="alert alert-warning alert-dismissible fade show" role="alert">
  Не найдено толкований слова "{{previosWord}}"
  <button type="button" class="close" v-on:click="closeWarning" aria-label="Закрыть">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово, значение которого вы хотите узнать</span>
    </p>
    <form class="form-inline" method="get">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" v-model="word" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" v-on:click.stop.prevent="onSubmit">Поиск</button>
    </form>
</div> 

        
<template v-if="descriptions && descriptions.length">
<blockquote v-for="(description, idx) of descriptions" v-bind:key="idx">
    <span class="badge badge-secondary">{{ description.vocab }}</span> - {{ description.def }}
    <template v-if="description.baseform">
        <br><span class="descr">{{ description.baseform }}</span>
    </template> 
    <template v-if="description.phongl">
        <br><span class="descr">{{ description.phongl }}</span>
    </template> 
    <template v-if="description.grclassgl">
        <br><span class="descr">{{ description.grclassgl }}</span>
    </template>
    <template v-if="description.stylgl">
        <br><span class="descr">{{ description.stylgl }}</span>
    </template> 
    <template v-if="description.anti">
        <br><span class="descr">противоп. <i>{{ description.anti }}</i></span>
    </template>        
    <template v-if="description.leglexam">
        <br><i>{{ description.leglexam }}</i>
    </template>         
</blockquote>        
</template>        
        
        <Waiting ref="waitingModal"></Waiting> 

    </div>
</template>

<script>

import axios from 'axios';
import Waiting from './modal/Waiting.vue';

export default {
    components: {
        Waiting
    },  
    props: {
        initialWord: {
            type: String,
            default: ''
        }
    },
    data: function() {
        return {
            word: this.initialWord,
            previousWord: '',
            descriptions: [],
            error: false,
            warning: false
        }
    },
    methods: {
        onSubmit: function () {   
            if (this.word.length) { 
                this.$modal.show('waiting-modal');
                this.error = false;
                this.warning = false;

                axios.get('http://combination-words.local/description/' + encodeURI(this.word))
                .then(response => {      
                    document.title = 'Значение слова "' + this.word + '"';  
                    this.previosWord = this.word;
                    this.descriptions = response.data.data;
                    this.error = false;
                    if (!this.descriptions.length) {
                        this.warning = true;
                    }
                })
                .catch(e => {
                    this.error = e;
                }).then(() => {
                    this.$modal.hide('waiting-modal');
                });
            }
        },
        closeError: function () {   
            this.error = false;
        },
        closeWarning: function () {   
            this.warning = false;
        }
    },   
    created: function () {
        if (this.word) {
            this.onSubmit();            
        }
    }
}
</script>

<style scoped>
blockquote {
    padding: 5px 20px;
    margin: 0 0 20px;
    border-left: 5px solid #eee;
}
</style>